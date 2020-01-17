package controllers

import dao.UserDao
import javax.inject.Inject
import play.api.mvc.{AbstractController, ControllerComponents}
import play.api.routing.JavaScriptReverseRouter

import scala.concurrent.ExecutionContext.Implicits.global
import tool.FormTool

/**
 * Created by Administrator on 2020/1/17
 */
class AppController @Inject()(cc: ControllerComponents, userDao: UserDao,formTool: FormTool) extends AbstractController(cc) {

  def loginBefore = Action { implicit request =>
    Ok(views.html.login())
  }

  def login = Action.async { implicit request =>
    val data = formTool.userForm.bindFromRequest().get
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

  def javascriptRoutes = Action { implicit request =>
    Ok(
      JavaScriptReverseRouter("jsRoutes")(


      )
    ).as("text/javascript")

  }


}
