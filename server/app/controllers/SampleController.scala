package controllers

import java.io.{File, FilenameFilter}

import dao.{ClassifyDao, GeneInfoDao, SampleDao}
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import utils.Utils
import models.Tables._
import tool.FormTool

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by Administrator on 2020/1/17
 */
class SampleController @Inject()(cc: ControllerComponents, sampleDao: SampleDao,
                                 geneInfoDao: GeneInfoDao, classifyDao: ClassifyDao) extends AbstractController(cc) {

  def deleteSampleBySampleName = Action.async { implicit request =>
    val data = FormTool.sampleNameForm.bindFromRequest().get
    val sampleName = data.sampleName
    sampleDao.deleteBySampleName(sampleName).zip(geneInfoDao.deleteBySampleName(sampleName)).zip(classifyDao.deleteById(sampleName)).map { x =>
      val filter = new FilenameFilter {
        override def accept(dir: File, name: String): Boolean = name.startsWith(sampleName)
      }
      Utils.dataFile.listFiles(filter).foreach { file =>
        file.delete()
      }
      Redirect(routes.SampleController.getAllSample())
    }
  }

  def getAllSample = Action.async { implicit request =>
    sampleDao.selectAll.map { x =>
      val array = getArrayBySamples(x)
      Ok(Json.toJson(array))
    }
  }

  def getArrayBySamples(x: Seq[SampleRow]) = {
    x.map { y =>
      val deleteStr = "<a title='删除' onclick=\"deleteProject('" + y.samplename + "')\" style='cursor: pointer;'><span><em class='fa fa-close'></em></span></a>"
      Json.obj(
        "samplename" -> y.samplename, "length" -> y.length, "definition" -> y.definition,
        "organism" -> y.organism, "operate" -> deleteStr, "taxonomy" -> y.taxonomy
      )
    }
  }

}
