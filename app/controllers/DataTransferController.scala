package controllers

import util.Codec
import user.Admin
import javax.inject._
import java.io.{File, FileOutputStream}
import java.nio.file.{Paths, Files}
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.http.HttpEntity
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import scala.concurrent.ExecutionContext
import com.typesafe.config.{Config, ConfigFactory}

@Singleton
class DataTransferController @Inject()(val controllerComponents: ControllerComponents)(implicit ec: ExecutionContext) extends BaseController {

	private val applicationConf: Config = ConfigFactory.load("application.conf")
	private val sharePath = applicationConf.getString("sharePath")

	def downloadFile(base64: String, preview: Boolean) = Action {
		implicit request: Request[AnyContent] => {
			val isAdmin: Boolean = Admin.verifyCookie(request.cookies)
			val uriDecoded: String = Codec.decodeBase64(base64)
			val absolutePath: String = Admin.getRealPath(uriDecoded, isAdmin)
			if (!isAdmin && !types.File.isSharePath(absolutePath)) {
				Forbidden("")
			}
			else {
				val file: File = new File(absolutePath)
				if (file.exists() && file.isFile()) {
					val uriEncodedFileName: String = Codec.encodeUri(file.getName())
					val source: Source[ByteString, _] = FileIO.fromPath(Paths.get(absolutePath))
					val contentType: String = types.File.getMimeType(absolutePath)
					if (preview) {
						Result(
							header = ResponseHeader(200, Map(
								// CONTENT_DISPOSITION -> s"attachment; filename=${uriEncodedFileName}"
							)),
							body = HttpEntity.Streamed(source, Some(file.length()), Some(contentType))
						)
					}
					else {
						Result(
							header = ResponseHeader(200, Map(
								CONTENT_DISPOSITION -> s"attachment; filename=${uriEncodedFileName}"
							)),
							body = HttpEntity.Streamed(source, Some(file.length()), Some(contentType))
						)
					}
				}
				else {
					NotFound("")
				}
			}
		}
	}

	def uploadFile() = Action {
		implicit request: Request[AnyContent] => {
			val formData = request.body.asMultipartFormData.get
			if (formData.badParts.length > 0) {
				Ok(Json.obj({"error" -> true}))
			}
			else if ((formData.files != None) && (formData.files.length > 0)) {
				val uploadDone: Boolean = (formData.dataParts.getOrElse("done", Seq("true"))(0) == "true")
				val base64: String = formData.dataParts.getOrElse("name", Seq("TmV3JTIwRmlsZQ=="))(0)
				val fileName: String = Codec.decodeBase64(base64)
				val publicPath: String = s"${this.sharePath}/public/"
				Files.createDirectories(Paths.get(publicPath))
				var filePath = s"${publicPath}${fileName}"
				val progressFilePath = s"${publicPath}${fileName}.scupload"
				val file = formData.files(0).ref
				val progressFile = new File(progressFilePath)
				if (!progressFile.exists()) {
					file.moveTo(Paths.get(progressFilePath), replace = true)
				}
				else {
					val byteArray: Array[Byte] = Files.readAllBytes(file)
					val outputFile = new FileOutputStream(progressFilePath, true)
					outputFile.write(byteArray)
					outputFile.close()
				}
				if (uploadDone) {
					var newFileName: String = fileName
					var ext: String = ""
					var fileBaseName: String = ""
					var renameCount: Int = 1
					val dotPosition = newFileName.lastIndexOf(".")
					if (dotPosition > 0) {
						ext = newFileName.substring(dotPosition, newFileName.length)
						fileBaseName = newFileName.substring(0, dotPosition)
					}
					else {
						fileBaseName = fileName
					}
					var renameSuccess: Boolean = false
					while (!renameSuccess && renameCount < 1000) {
						try {
							Files.move(Paths.get(progressFile.getAbsolutePath()), Paths.get(filePath))
							renameSuccess = true
						} catch {
							case e: Exception => {
								filePath = s"${publicPath}${fileBaseName}_${renameCount}${ext}"
								renameCount += 1
							}
						}
					}
				}
				Ok(Json.obj({"error" -> false}))
			}
			else {
				Ok(Json.obj({"error" -> true}))
			}
		}
	}

}
