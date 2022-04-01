import scala.io.Source
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem

trait ParseFileActorProtocol

object StopParseFileActor extends ParseFileActorProtocol;

case class ParseFileData(newData: String) extends ParseFileActorProtocol;

object ParseFileActor {
    val convertDataActor = ActorSystem(ConvertDataActor(), "fileParser");

    def parseFileFrom(path: String): Unit = {
        // https://alvinalexander.com/scala/how-to-open-read-text-files-in-scala-cookbook-examples/
        // Drop first 4 lines since they're just headers
        for (line <- Source.fromFile(path).getLines.drop(4)) {
            convertDataActor ! DataToConvert(line);
        }

        // Quit the convert data actor afterwards
        convertDataActor ! EndConvertDataActor;
    }

    def apply(): Behavior[ParseFileActorProtocol] = {
        Behaviors.receive((context, message) => {
            message match {
                case StopParseFileActor =>
                    context.log.error("Not a valid file path...")
                    context.log.info("Terminating actor...")
                    Behaviors.stopped;
                case ParseFileData(newData) =>
                    context.log.info("Valid file path: " + message + ". Now parsing...")
                    parseFileFrom(newData);
                    Behaviors.same;
            }
        })
    }
}