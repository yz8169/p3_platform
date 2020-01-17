package controllers

import dao.UserDao
import javax.inject.Inject
import play.api.mvc.{AbstractController, ControllerComponents}
import tool.FormTool._
import play.api.libs.concurrent.Execution.Implicits._

/**
 * Created by Administrator on 2020/1/17
 */
class AppController @Inject()(cc: ControllerComponents, userDao: UserDao) extends AbstractController(cc) {

  def loginBefore = Action { implicit request =>
    Ok(views.html.login())
  }

  def login = Action.async { implicit request =>
    val data = userForm.bindFromRequest().get
    userDao.selectByUserData(data).map { optionUser =>
      if (optionUser.isDefined) {
        val user = optionUser.get
        val rSession = request.session + ("user" -> data.account) + ("id" -> user.id.toString)
        Redirect(routes.GenomeController.sampleManageBefore()).withSession(rSession)
      } else {
        Redirect(routes.AppController.loginBefore()).flashing("info" -> "账号或密码错误!")
      }
    }
  }


}
