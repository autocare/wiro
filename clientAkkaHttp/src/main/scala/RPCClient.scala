package wiro.client

import wiro.models.Config
import wiro.server.akkaHttp.MethodMetaData

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal

import akka.stream.ActorMaterializer
import akka.actor.ActorSystem

import scala.concurrent.{ Future, ExecutionContext }

import io.circe._
import io.circe.syntax._;
import de.heikoseeberger.akkahttpcirce.CirceSupport._

trait RPCClientContext[T] {
  def methodsMetaData: Map[String, MethodMetaData]
  def tp: Seq[String]
  def path: String = tp.last
}

class RPCClient(
  config: Config,
  ctx: RPCClientContext[_]
)(implicit
  system: ActorSystem,
  materializer: ActorMaterializer,
  executionContext: ExecutionContext
) extends autowire.Client[Json, Decoder, Encoder] {
  def write[Result: Encoder](r: Result): Json = r.asJson

  def read[Result: Decoder](p: Json): Result = {
    val right = Json.obj("Right" -> Json.obj("b" -> p))
    val left = Json.obj("Left" -> Json.obj("a" -> p))
    (left.as[Result], right.as[Result]) match {
      case (_, Right(result)) => result
      case (Right(result), _) => result
      case (Left(error1), Left(error2))  =>
        throw new Exception(error1.getMessage + error2.getMessage)
    }
  }

  override def doCall(req: Request): Future[Json] = {
    val completePath = req.path.mkString(".")
    //we're trying to match here the paths generated by two different macros
    //if it fails at runtime it means something is wrong in the implementation
    val methodMetaData = ctx.methodsMetaData
      .getOrElse(completePath, throw new Exception("Runtime mismatch between metadata and method name"))
    val operationName = methodMetaData.operationType.name.getOrElse(req.path.last)
    val uri = s"http://${config.host}:${config.port}/${ctx.path}/$operationName"
    val method = HttpMethods.POST

    val request = HttpRequest(
      uri = uri,
      method = method,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = req.args.asJson.noSpaces
      )
    )

    Http().singleRequest(request)
      .flatMap { response => Unmarshal(response.entity).to[Json] }
  }
}
