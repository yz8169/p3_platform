package controllers

import java.io.File

import dao._
import javax.inject.Inject
import models.Tables._
import play.api.libs.json.Json
import play.api.mvc.{Action, _}
import tool.{FormTool, Tool}
import utils.Utils

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by yz on 2018/5/10
 */
class ToolController @Inject()(cc: ControllerComponents, projectDao: ProjectDao, formTool: FormTool, tool: Tool) extends
  AbstractController(cc) {


  def getExampleFile = Action { implicit request =>
    val data = formTool.fileNameForm.bindFromRequest().get
    val file = new File(Utils.path, s"example/${data.fileName}")
    Ok.sendFile(file).as(TEXT)
  }

  def downloadExampleFile = Action { implicit request =>
    val data = formTool.fileNameForm.bindFromRequest().get
    val file = new File(Utils.path, s"example/${data.fileName}")
    Ok.sendFile(file).withHeaders(
      CACHE_CONTROL -> "max-age=3600",
      CONTENT_DISPOSITION -> s"attachment; filename=${
        file.getName
      }",
      CONTENT_TYPE -> "application/x-download"
    )
  }


}
