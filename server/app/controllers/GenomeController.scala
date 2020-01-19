package controllers

import java.io.{File, FileFilter}

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.stream.Materializer
import dao.{ClassifyDao, GeneInfoDao, MissionDao, SampleDao, UserDao}
import javax.inject.Inject
import org.apache.commons.io.FileUtils
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, ControllerComponents, WebSocket}
import tool.{FormTool, Tool}
import utils.{MissionExecutor, Utils}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import models.Tables._
import implicits.Implicits._
import org.joda.time.DateTime
import org.zeroturnaround.zip.ZipUtil
import play.api.libs.streams.ActorFlow

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps


/**
 * Created by Administrator on 2020/1/17
 */
class GenomeController @Inject()(cc: ControllerComponents, userDao: UserDao, tool: Tool, sampleDao: SampleDao,
                                 geneInfoDao: GeneInfoDao, classifyDao: ClassifyDao, formTool: FormTool,
                                 missionDao: MissionDao)(implicit val system: ActorSystem,
                                                         implicit val materializer: Materializer) extends
  AbstractController(cc) {

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
        val command1 =
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

  def toCrisprHelp = Action { implicit request =>
    Ok(views.html.user.crisprHelp())
  }

  def crisprBefore = Action { implicit request =>
    Ok(views.html.user.crispr())
  }

  def crisprResult = Action { implicit request =>
    val data = formTool.missionIdForm.bindFromRequest().get
    val resultDir = tool.getResultDirById(data.missionId)
    val file = new File(resultDir, "out/result.json")
    val jsonStr = FileUtils.readFileToString(file)
    val json = Json.parse(jsonStr)
    Ok(json)
  }

  def updateMissionSocket(kind: String) = WebSocket.accept[JsValue, JsValue] {
    implicit request =>
      val userId = tool.getUserId
      var beforeMissions = Utils.execFuture(missionDao.selectAll(userId, kind))
      var currentMissions = beforeMissions
      ActorFlow.actorRef(out => Props(new Actor {
        override def receive: Receive = {
          case msg: JsValue if (msg \ "info").as[String] == "start" =>
            out ! Utils.getJsonByTs(beforeMissions)
            system.scheduler.scheduleOnce(3 seconds, self, Json.obj("info" -> "update"))
          case msg: JsValue if (msg \ "info").as[String] == "update" =>
            missionDao.selectAll(userId, kind).map {
              missions =>
                currentMissions = missions
                if (currentMissions.size != beforeMissions.size) {
                  out ! Utils.getJsonByTs(currentMissions)
                } else {
                  val b = currentMissions.zip(beforeMissions).forall {
                    case (currentMission, beforeMission) =>
                      currentMission.id == beforeMission.id && currentMission.state == beforeMission.state
                  }
                  if (!b) {
                    out ! Utils.getJsonByTs(currentMissions)
                  }
                }
                beforeMissions = currentMissions
                system.scheduler.scheduleOnce(3 seconds, self, Json.obj("info" -> "update"))
            }
          case _ =>
            self ! PoisonPill
        }

        override def postStop(): Unit = {
          self ! PoisonPill
        }

      }))

  }

  def crispr = Action(parse.multipartFormData) { implicit request =>
    val data = formTool.crisprForm.bindFromRequest.get
    val missionName = formTool.missionNameForm.bindFromRequest().get.missionName
    val userId = tool.getUserId
    val kind = "crispr"
    val args = ArrayBuffer(
      s"Minimal Repeat Length:${data.minDR}",
      s"Maximal Repeat Length:${data.maxDR}",
      s"Allow Repeat Mismatch:${if (data.arm.isDefined) "yes" else "no"}",
      s"Minimal Spacers size in function of Repeat size:${data.percSPmin}",
      s"Maximal Spacers size in function of Repeat size:${data.percSPmax}",
      s"Maximal allowed percentage of similarity between Spacers:${data.spSim}",
      s"Percentage mismatchs allowed between Repeats:${data.mismDRs}",
      s"Percentage mismatchs allowed for truncated Repeat:${data.truncDR}",
      s"Size of Flanking regions in base pairs (bp) for each analyzed CRISPR array:${data.flank}",
      s"Alternative detection of truncated repeat:${if (data.dt.isDefined) "yes" else "no"}",
      s"Perform CAS gene detection:${if (data.cas.isDefined) "yes" else "no"}"
    )
    val defValue = data.defValue match {
      case "S" => "SubTyping"
      case "T" => "Typing"
      case "G" => "General (permissive)"
    }
    if (data.cas.isDefined) {
      args ++= ArrayBuffer(
        s"Clustering model:${defValue}",
        s"Unordered:${if (data.meta.isDefined) "yes" else "no"}"
      )
    }
    val argStr = args.mkString(";")
    val row = MissionRow(0, s"${missionName}", userId, kind, argStr, new DateTime(), None, "running")
    val missionExecutor = new MissionExecutor(missionDao, tool, row)
    val (tmpDir, resultDir) = (missionExecutor.workspaceDir, missionExecutor.resultDir)
    val seqFile = new File(tmpDir, "seq.fa")
    data.method match {
      case "text" =>
        FileUtils.writeStringToFile(seqFile, data.queryText)
      case "file" =>
        val file = request.body.file("file").get
        file.ref.moveTo(seqFile, replace = true)
    }
    val commandBuffer = ArrayBuffer(s"export PATH=$$PATH:/${Utils.dosPath2Unix(Utils.vmatchDir)}\n", s"perl ${Utils.dosPath2Unix(Utils.crisprDir)}/CRISPRCasFinder.pl -in ${Utils.dosPath2Unix(seqFile)} -out ${Utils.dosPath2Unix(resultDir)}/out -so ${Utils.dosPath2Unix(Utils.vmatchDir)}/SELECT/sel392v2.so " +
      s"-drpt ${Utils.dosPath2Unix(Utils.crisprDir)}/supplementary_files/repeatDirection.tsv -rpts ${Utils.dosPath2Unix(Utils.crisprDir)}/supplementary_files/Repeat_List.csv " +
      s"-minDR ${data.minDR} -maxDR ${data.maxDR} -percSPmin ${data.percSPmin} -percSPmax ${data.percSPmax} " +
      s"-spSim ${data.spSim} -mismDRs ${data.mismDRs}  -truncDR ${data.truncDR} -flank ${data.flank} " +
      s"-cf ${Utils.dosPath2Unix(Utils.crisprDir)}/CasFinder-2.0.2 ")
    if (data.arm.isEmpty) commandBuffer += "-noMism"
    if (data.dt.isDefined) commandBuffer += "-betterDetectTrunc"
    if (data.cas.isDefined) {
      commandBuffer += "-cas -rcfowce "
      commandBuffer += s"-def ${data.defValue}"
      if (data.meta.isDefined) commandBuffer += "-meta"
    }
    val shBuffer = ArrayBuffer(commandBuffer.mkString(" "))
    missionExecutor.execLinux(shBuffer)
    Redirect(routes.GenomeController.getAllMission() + s"?kind=${kind}")
  }

  def getAllMission = Action.async {
    implicit request =>
      val data = formTool.kindForm.bindFromRequest().get
      val kind = data.kind
      val userId = tool.getUserId
      missionDao.selectAll(userId, kind).map {
        x =>
          val array = Utils.getArrayByTs(x)
          Ok(Json.toJson(array))
      }
  }

  def missionNameCheck = Action.async { implicit request =>
    val data = formTool.missionForm.bindFromRequest.get
    val userId = tool.getUserId
    missionDao.selectOptionByMissionAttr(userId, data.missionName, data.kind).map { mission =>
      mission match {
        case Some(y) => Ok(Json.obj("valid" -> false))
        case None =>
          Ok(Json.obj("valid" -> true))
      }
    }
  }

  def downloadResult = Action.async {
    implicit request =>
      val userId = tool.getUserId
      val data = formTool.missionIdForm.bindFromRequest().get
      val missionId = data.missionId
      missionDao.selectByMissionId(userId, missionId).map {
        mission =>
          val missionIdDir = tool.getMissionIdDirById(missionId)
          val resultDir = new File(missionIdDir, "result")
          val resultFile = new File(missionIdDir, s"result.zip")
          if (!resultFile.exists()) ZipUtil.pack(resultDir, resultFile)
          Ok.sendFile(resultFile).withHeaders(
            CACHE_CONTROL -> "max-age=3600",
            CONTENT_DISPOSITION -> s"attachment; filename=${
              mission.missionname
            }.zip",
            CONTENT_TYPE -> "application/x-download"
          )
      }
  }

  def downloadResultFile = Action.async {
    implicit request =>
      val userId = tool.getUserId
      val data = formTool.missionFileForm.bindFromRequest().get
      val missionId = data.missionId
      missionDao.selectByMissionId(userId, missionId).map {
        mission =>
          val missionIdDir = tool.getMissionIdDirById(missionId)
          val resultDir = new File(missionIdDir, "result")
          val file = new File(resultDir, data.fileName)
          Ok.sendFile(file).withHeaders(
            //            CACHE_CONTROL -> "max-age=3600",
            CONTENT_DISPOSITION -> s"attachment; filename=${
              file.getName
            }",
            CONTENT_TYPE -> "application/x-download"
          )
      }
  }

  def getLogContent = Action.async {
    implicit request =>
      val userId = tool.getUserId
      val data = formTool.missionIdForm.bindFromRequest().get
      missionDao.selectByMissionId(userId, data.missionId).map {
        mission =>
          val missionIdDir = tool.getMissionIdDirById(data.missionId)
          val logFile = new File(missionIdDir, s"log.txt")
          val logStr = FileUtils.readFileToString(logFile, "UTF-8")
          Ok(Json.toJson(logStr))
      }
  }

  def deleteMissionById = Action.async {
    implicit request =>
      val data = formTool.deleteMissionForm.bindFromRequest().get
      missionDao.deleteById(data.missionId).map {
        x =>
          val missionIdDir = tool.getMissionIdDirById(data.missionId)
          Utils.deleteDirectory(missionIdDir)
          Redirect(routes.GenomeController.getAllMission() + "?kind=" + data.kind)
      }
  }

  def phyTreeBefore = Action { implicit request =>
    Ok(views.html.user.phyTree())
  }

  def phyTreeResult = Action { implicit request =>
    val data = formTool.missionIdForm.bindFromRequest().get
    val resultDir = tool.getResultDirById(data.missionId)
    val treeFile = new File(resultDir, "tree.newick")
    val treeStr = FileUtils.readFileToString(treeFile)
    Ok(Json.obj("tree" -> treeStr))
  }

  def phyTree = Action { implicit request =>
    val data = formTool.phyTreeForm.bindFromRequest.get
    if (data.sampleNames.contains(data.refSampleName)) {
      Ok(Json.obj("valid" -> "false", "message" -> "样品不能包含参考样品!"))
    } else {
      val missionName = formTool.missionNameForm.bindFromRequest().get.missionName
      val userId = tool.getUserId
      val kind = "phyTree"
      val args = ArrayBuffer(
        s"Reference sample:${data.refSampleName}",
        s"Sample:${data.sampleNames.mkString("<br>")}"
      )
      val refSampleName = tool.getSampleName(data.refSampleName)
      val sampleNames = data.sampleNames.map(x => tool.getSampleName(x))
      val argStr = args.mkString(";")
      val row = MissionRow(0, s"${missionName}", userId, kind, argStr, new DateTime(), None, "running")
      val missionExecutor = new MissionExecutor(missionDao, tool, row)
      val (tmpDir, resultDir) = (missionExecutor.workspaceDir, missionExecutor.resultDir)
      val file = tool.getGenomeFile(refSampleName)
      FileUtils.copyFileToDirectory(file, tmpDir)
      val lines = ArrayBuffer(s"ref=${file.getName}")
      sampleNames.foreach { sampleName =>
        val file = tool.getGenomeFile(sampleName)
        FileUtils.copyFileToDirectory(file, tmpDir)
        lines += s"${sampleName}=${file.getName}"
      }
      val faListFile = new File(tmpDir, "seq.list")
      FileUtils.writeLines(faListFile, lines.asJava)
      val command =
        s"""
           |dos2unix *
           |perl ${Utils.dosPath2Unix(Utils.binPath)}/09.Snp/src/find_repeat_for_bac_snp.pl  --ref ${file.getName} -out dup.out
           |perl ${Utils.dosPath2Unix(Utils.binPath)}/09.Snp/src/MUMmerSnpPipline.V2.3.pl -lib seq.list -dup dup.out -dir out -readlist reads_info.list -pileup_q 20 -pileup_e 5 -pileup_n 10
           |perl ${Utils.dosPath2Unix(Utils.binPath)}/13.Phylogenetic_tree/src/snp2fa_v2.pl out/clean.snp.pileup2 all.mfa ${refSampleName}
           |perl ${Utils.dosPath2Unix(Utils.binPath)}/13.Phylogenetic_tree/src/02.phylogeny/bin/phylo_tree.pl all.mfa -format mfa -type ml -b '-4' -d nt -outdir treeOut
           |cp treeOut/tree.png ${Utils.dosPath2Unix(resultDir)}/
           |cp treeOut/tree.newick ${Utils.dosPath2Unix(resultDir)}/
           |cp treeOut/tree.svg ${Utils.dosPath2Unix(resultDir)}/
           |cp treeOut/tree.root.svg ${Utils.dosPath2Unix(resultDir)}/
               """.stripMargin
      val shBuffer = ArrayBuffer(command)
      missionExecutor.execLinux(shBuffer)
      Redirect(routes.GenomeController.getAllMission() + s"?kind=${kind}")
    }
  }


}
