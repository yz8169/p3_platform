package controllers

import java.io.{File, FileFilter}

import dao.{ClassifyDao, GeneInfoDao, SampleDao, UserDao}
import javax.inject.Inject
import org.apache.commons.io.FileUtils
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import tool.Tool
import utils.Utils

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import models.Tables._
import implicits.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global


/**
 * Created by Administrator on 2020/1/17
 */
class GenomeController @Inject()(cc: ControllerComponents, userDao: UserDao, tool: Tool, sampleDao: SampleDao,
                                 geneInfoDao: GeneInfoDao, classifyDao: ClassifyDao) extends AbstractController(cc) {

  def addSampleDataBefore = Action { implicit request =>
    Ok(views.html.user.addSampleData())
  }

  def sampleManageBefore = Action { implicit request =>
    Ok(views.html.user.sampleManage())
  }

  def addSampleData = Action(parse.multipartFormData) { implicit request =>
    var b = true
    var message = ""
    request.body.files.find { file =>
      try {
        val tmpDir = tool.createTempDirectory("tmpDir")
        val gbffFile = new File(tmpDir, "data.gbff")
        file.ref.moveTo(gbffFile, true)
        val pyFile = new File(Utils.pyPath, "readGenBank.py")
        val command1=
          s"""
             |python ${pyFile.unixPath}
             |""".stripMargin
        val execCommand = Utils.callLinuxScript(tmpDir, shBuffer = ArrayBuffer(command1))
        if (!execCommand.isSuccess) {
          tool.deleteDirectory(tmpDir)
          message = execCommand.getErrStr
          b = false
        } else {
          val infoFile = new File(tmpDir, "info.txt")
          val infoLines = FileUtils.readLines(infoFile).asScala.drop(1)
          val infos = infoLines.map { x =>
            val columns = x.split("\t")
            SampleRow(columns(0), columns(1).toInt, columns(4), columns(6), columns(7))
          }
          val clssifys = infos.map { x =>
            val classify = x.taxonomy
            val columns = classify.split(",")
            ClassifyRow(x.samplename, columns(1), columns(2), columns(3), columns(4), columns(5), columns(6))
          }
          val geneInfoFile = new File(tmpDir, "geneInfo.txt")
          val geneInfoLines = FileUtils.readLines(geneInfoFile).asScala.drop(1)
          val geneInfos = geneInfoLines.map { x =>
            val columns = x.split("\t")
            GeneinfoRow(columns(0), columns(7), columns(1), columns(2).toInt, columns(3).toInt, columns(4),
              columns(6), columns(8), columns(9))
          }
          val columns = infoLines.head.split("\t")
          val sampleId = columns(0)
          val kind = columns(8)
          var isExec = true
          if (geneInfoLines.isEmpty) {
            val command =
              s"""
                 |perl ${Utils.dosPath2Unix(Utils.binPath)}/01.Gene-prediction/bin/gene-predict_v2.pl --SpeType B --SampleId ${sampleId} --genemark --verbose -shape ${kind} --cpu 10  --run multi -outdir output ${Utils.dosPath2Unix(new File(tmpDir, sampleId + ".genome.fa"))}
                 |cp ${Utils.dosPath2Unix(tmpDir)}/output/final/${sampleId}.cds ${Utils.dosPath2Unix(tmpDir)}/${sampleId}.cds.fa
                 |cp ${Utils.dosPath2Unix(tmpDir)}/output/final/${sampleId}.pep ${Utils.dosPath2Unix(tmpDir)}/${sampleId}.pep.fa
                 |cp ${Utils.dosPath2Unix(tmpDir)}/output/final/${sampleId}.gff ${Utils.dosPath2Unix(tmpDir)}/
             """.stripMargin
            val execCommand = Utils.callLinuxScript(tmpDir, ArrayBuffer(command))
            if (!execCommand.isSuccess) {
              tool.deleteDirectory(tmpDir)
              message = execCommand.getErrStr
              b = false
              isExec = false
            } else {
              val parent = new File(tmpDir, "output/final")
              val gffFile = new File(parent, s"${sampleId}.gff")
              val cdsFile = new File(parent, s"${sampleId}.cds")
              val pepFile = new File(parent, s"${sampleId}.pep")
              geneInfos ++= tool.getGeneInfos(cdsFile, pepFile, gffFile, sampleId)
            }
          }
          if (isExec) {
            val filter = new FileFilter {
              override def accept(file: File): Boolean = file.getName.endsWith(".fa") || file.getName.endsWith(".gff") ||
                file.getName.endsWith(".gbff") && file.getName != "data.gbff"
            }
            FileUtils.copyDirectory(tmpDir, Utils.dataFile, filter)
            val f = sampleDao.updateAll(infos.toList).zip(geneInfoDao.updateAll(geneInfos.toList)).
              zip(classifyDao.updateAll(clssifys.toList)).map { x =>
              tool.deleteDirectory(tmpDir)
            }
            Utils.execFuture(f)
          }
        }
      } catch {
        case x: Exception => b = false
          x.printStackTrace
          message = x.toString
      }
      b == false
    }
    if (b) {
      Ok(Json.toJson("success"))
    } else {
      Ok(Json.obj("valid" -> "false", "message" -> message))
    }

  }


}
