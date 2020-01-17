package myJs.user

import myJs.Tool

import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import myJs.myPkg.jquery._
import myJs.myPkg.Implicits._
import myJs.myPkg.bootstrap.Bootstrap.default._
import org.scalajs.dom.FormData
import org.scalajs.dom.raw.HTMLFormElement
import scalatags.Text.all._
import myJs.Utils._
import myJs.Tool._
import myJs.myPkg.{ColumnOptions, ExportOptions, LayerOptions, Swal, SwalOptions, TableOptions}
import org.scalajs.dom._

import scala.collection.SeqMap
import scala.scalajs.js
import scala.scalajs.js.JSON

/**
 * Created by Administrator on 2020/1/17
 */
@JSExportTopLevel("SampleManage")
object SampleManage {

  @JSExport("init")
  def init = {

    bootStrapValidator

    val map = SeqMap(
      "种名" -> "organism",
      "基因组长度" -> "length",
      "描述" -> "definition",
      "分类学信息" -> "taxonomy"
    )
    val html = map.map { case (key, myV) =>
      label(marginRight := "15px",
        input(`type` := "checkbox", checked := "checked", value := key, onclick := s"Utils.setColumns('${key}')",
          key))
    }.mkString("&nbsp;")
    $("#checkbox").html(html)

    addShow


  }

  @JSExport("addShow")
  def addShow = {
    jQuery("#addModal").modal("show")
  }

  def add = {
    val formData = new FormData(document.getElementById("form").asInstanceOf[HTMLFormElement])
    val element = div(id := "content",
      span(id := "info", "正在上传数据文件",
        span(id := "progress", "。。。")), " ",
      img(src := "/assets/images/running2.gif", cls := "runningImage", width := 30, height := 20)
    ).render
    val index = layer.alert(element, Tool.layerOptions)
    val url = g.jsRoutes.controllers.AdminController.updateMetaboDb().url.toString
    val xhr = new XMLHttpRequest
    xhr.open("post", url)
    xhr.upload.onprogress = progressHandlingFunction
    xhr.onreadystatechange = (e) => {
      if (xhr.readyState == XMLHttpRequest.DONE) {
        val data = xhr.response
        val rs = JSON.parse(data.toString).asInstanceOf[js.Dictionary[String]]
        if (rs.get("error").isEmpty) {
          refreshTable { () =>
            layer.close(index)
            jQuery("#addModal").modal("hide")
            val options = SwalOptions.`type`("success").title("成功!").text("数据库覆盖完成!")
            Swal.swal(options)
          }
        } else {
          layer.close(index)
          layer.msg(rs("error"), LayerOptions.icon(5).time(3000))
        }
      }
    }
    xhr.send(formData)

  }

  def refreshTable(f: () => js.Any = () => (), showLayer: Boolean = false) = {
    val index = if (showLayer) layer.alert(Tool.loadingElement, Tool.layerOptions) else 0
    val url = g.jsRoutes.controllers.AdminController.getAllMetaboDb().url.toString
    val ajaxSettings = JQueryAjaxSettings.url(s"${url}?").contentType("application/json").
      `type`("get").success { (data, status, e) =>
      val rs = data.asInstanceOf[js.Array[js.Dictionary[String]]]
      refreshTableContent(data)
      layer.close(index)
      f()
    }
    $.ajax(ajaxSettings)

  }

  def refreshTableContent(data: js.Any) = {
    val rs = data.asInstanceOf[js.Dictionary[js.Any]]
    val columnNames = rs("columnNames").asInstanceOf[js.Array[String]]
    val columns = columnNames.map { columnName =>
      val title = columnName match {
        case "index" => "Index"
        case "name" => "Name"
        case "strandardName" => "Standard.name"
        case "superclass" => "Class"
        case "keggid" => "KeggID"
        case "accession" => "HMDBID"
        case "keggId" => "KeggID"
        case _ => columnName
      }
      val fmt = tbFmt(columnName)
      ColumnOptions.field(columnName).title(title).formatter(fmt).sortable(true)
    }
    val exportOptions = ExportOptions.csvSeparator("\t").fileName("user_str_db").exportHiddenColumns(true)
    val options = TableOptions.data(rs("array")).columns(columns).exportOptions(exportOptions).
      exportHiddenColumns(true)
    $("#table").bootstrapTable("destroy").bootstrapTable(options)
    columnNames.drop(9).foreach { x =>
      $("#table").bootstrapTable("hideColumn", x)
      $(s"input:checkbox[value='${x}']").attr("checked", false)
    }

  }

  def tbFmt(columnName: String): js.Function = (v: js.Any) => columnName match {
    case _ => v
  }

  def bootStrapValidator = {
    val dict = js.Dictionary(
      "feedbackIcons" -> js.Dictionary(
        "valid" -> "glyphicon glyphicon-ok",
        "invalid" -> "glyphicon glyphicon-remove",
        "validating" -> "glyphicon glyphicon-refresh",
      ),
      "fields" -> js.Dictionary(
        "file" -> js.Dictionary(
          "validators" -> js.Dictionary(
            "notEmpty" -> js.Dictionary(
              "message" -> "请选择一个数据文件！"
            ),
            "file" -> js.Dictionary(
              "extension" -> "gbff,gbk,gb",
              "message" -> "请选择一个GeneBank格式的数据文件!",
            ),
          )
        ),
      )
    )
    g.$("#form").bootstrapValidator(dict)

  }


}
