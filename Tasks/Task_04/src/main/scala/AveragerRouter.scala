import AveragerActor.{AveragerActorProtocol, HandleNewTickData, ListingResponse, handleDBRef}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{Behaviors, Routers}

object AveragerRouter {

    sealed trait AveragerRouterProtocol extends ActorProtocolSerializable

    case class HandleTickData(newTick: Tick) extends AveragerRouterProtocol
    case class Terminate() extends AveragerRouterProtocol
    case class ListingResponse(listing: Receptionist.Listing) extends AveragerRouterProtocol

    val serviceKey = ServiceKey[AveragerRouterProtocol]("averagerRouter")

    def apply(): Behavior[AveragerRouterProtocol] = {
        Behaviors.setup[AveragerRouterProtocol] { context =>
            context.log.info("AveragerRouter - starting pool")
            context.system.receptionist ! Receptionist.register(this.serviceKey, context.self)

            // Could probably also use group routers here but it's really convenient that the pool routers start their routees when they start
            val pool = Routers.pool(poolSize = 10) {
                // make sure the workers are restarted if they fail
                Behaviors.supervise(AveragerActor()).onFailure[Exception](SupervisorStrategy.restart)
            }
            val poolRouter = context.spawn(
              pool.withConsistentHashingRouting(1, _.symbolIdentifier),
              "AveragerRouter"
            )

            // Broadcast
            val poolWithBroadcast = pool.withBroadcastPredicate(_.isInstanceOf[AveragerActor.Terminate])
            val routerWithBroadcast = context.spawn(poolWithBroadcast, "AveragerRouterBroadcast")

            // Subscription to db actor
            context.system.receptionist ! Receptionist.register(this.serviceKey, context.self)
            val subscriptionAdapter = context.messageAdapter[Receptionist.Listing](ListingResponse.apply)

            context.system.receptionist ! Receptionist.Subscribe(
              DatabaseConnectorActor.serviceKey,
              subscriptionAdapter
            )

            Behaviors.receiveMessagePartial {
                case ListingResponse(DatabaseConnectorActor.serviceKey.Listing(listings)) =>
                    listings.headOption match {
                        case Some(dbActorRef) =>
                            context.log.info(
                              "Using db actor ref {} and broadcast router {}",
                              dbActorRef,
                              routerWithBroadcast
                            )
                            handleDBRef(dbActorRef, routerWithBroadcast, poolRouter)
                        case None =>
                            Behaviors.same
                    }
            }
        }
    }

    private def handleDBRef(
        dbActorRef: ActorRef[DatabaseConnectorActor.DatabaseConnectorActorProtocol],
        broadcastRouter: ActorRef[AveragerActor.AveragerActorProtocol],
        poolRouter: ActorRef[AveragerActor.AveragerActorProtocol]
    ): Behavior[AveragerRouterProtocol] = Behaviors.setup { context =>
        context.log.info("Handledbref setup call")
        Behaviors.receiveMessagePartial {
            case this.Terminate() =>
                context.system.receptionist ! Receptionist.Deregister(
                  this.serviceKey,
                  context.self
                )
                context.log.info("AveragerRouter - Terminating averagers with router {}", broadcastRouter)
                broadcastRouter ! AveragerActor.Terminate()
                context.log.info("Terminating Averager Router and averager actors")
                dbActorRef ! DatabaseConnectorActor.Terminate()
                Behaviors.stopped
            case HandleTickData(newTick) =>
                poolRouter ! AveragerActor.HandleNewTickData(newTick)
                Behaviors.same
        }
    }

}
