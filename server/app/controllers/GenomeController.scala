package controllers

import dao.UserDao
import javax.inject.Inject
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{AbstractController, ControllerComponents}
import tool.FormTool._

/**
 * Created by Administrator on 2020/1/17
 */
class GenomeController @Inject()(cc: ControllerComponents, userDao: UserDao) extends AbstractController(cc) {

  def sampleManageBefore = Action { implicit request =>
    Ok(views.html.user.sampleManage())
  }



}
