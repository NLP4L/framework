/*
 * Copyright 2015 org.NLP4L
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nlp4l.framework.controllers

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Success

import com.google.inject.name.Named

import akka.actor.ActorRef
import javax.inject.Inject
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.mvc.Action
import play.api.mvc.Controller

import org.joda.time.DateTime
import org.nlp4l.framework.dao.JobDAO
import org.nlp4l.framework.dao.RunDAO
import org.nlp4l.framework.models.ActionResult
import org.nlp4l.framework.models.Cell
import org.nlp4l.framework.models.CellAttribute
import org.nlp4l.framework.models.CellType
import org.nlp4l.framework.models.DbModels.fWActionResultWrites
import org.nlp4l.framework.models.DbModels.fWJobStatusWrites
import org.nlp4l.framework.models.DbModels.fWJobWrites
import org.nlp4l.framework.models.DbModels.fWRecordWithAttrubuteWrites
import org.nlp4l.framework.models.Dictionary
import org.nlp4l.framework.models.DictionaryAttribute
import org.nlp4l.framework.models.Job
import org.nlp4l.framework.models.JobMessage
import org.nlp4l.framework.models.Record
import org.nlp4l.framework.models.RecordWithAttrbute
import org.nlp4l.framework.models.Replay
import org.nlp4l.framework.processors.DeployerChain
import org.nlp4l.framework.processors.ProcessorChain2
import org.nlp4l.framework.processors.ProcessorChain2Builder
import org.nlp4l.framework.processors.ValidatorChain



class JobController @Inject()(jobDAO: JobDAO, runDAO: RunDAO, @Named("processor-actor2") processActor: ActorRef) extends Controller {

  def list() = Action.async {request =>
    val offset = request.getQueryString("offset") match {
      case Some(x) if x != "" => x.toInt
      case _ => 0
    }
    val size = request.getQueryString("limit") match {
      case Some(x) => x.toInt
      case _ => 10
    }
    val sort = request.getQueryString("sort") match {
      case Some(c) => c
      case _ => "jobId"
    }
    val order = request.getQueryString("order") match {
      case Some(c) => c
      case _ => "asc"
    }
    var total = 0
    jobDAO.fetchAll().map {
      res =>
        total = res.size
    }
    jobDAO.fetch(sort, order, offset, size).map {
      res =>
        val jsonResponse = Json.obj(
          "total" -> total,
          "rows" -> Json.toJson(res)
        )
        Ok(jsonResponse)
    }
  }

 

  
  def saveJobConfig(jobId: Int) = Action.async(parse.multipartFormData) {request =>
    jobDAO.get(jobId) map {job =>
        request.body.file("config") map { file =>
          val uuid = UUID.randomUUID().toString
          val temp = new File(s"/tmp/$uuid")
          file.ref.moveTo(temp, replace = true)
          val tempPath = Paths.get(temp.getAbsolutePath)
          val configdata: String = Files.readAllLines(tempPath).toList.mkString("\n")
          if (ProcessorChain2.validateConf(configdata)) {
            // reload processor chain
            ProcessorChain2.loadChain(jobDAO, jobId)
            // Update DB
            val newjob = Job(Some(jobId), job.name, configdata, job.lastRunId, job.lastRunAt, job.lastDeployAt)
            jobDAO.update(newjob)
            val runIdList: Seq[Int] = runDAO.selectRunList(jobId, newjob.lastRunId)
            Ok(org.nlp4l.framework.views.html.editjob(newjob, runIdList, "Successfully uploaded.", ""))
          } else {
            Files.delete(Paths.get(temp.getAbsolutePath))
            val runIdList: Seq[Int] = runDAO.selectRunList(jobId, job.lastRunId)
            Ok(org.nlp4l.framework.views.html.editjob(job, runIdList, "", "Upload failed. May be an invalid config file?"))
          }
        } getOrElse {
          val runIdList: Seq[Int] = runDAO.selectRunList(jobId, job.lastRunId)
          Ok(org.nlp4l.framework.views.html.editjob(job, runIdList, "", "Upload failed."))
        }
    } recover {
      case e => NotFound(org.nlp4l.framework.views.html.notFound(e.getMessage))
    }
  }
  
  def saveNewJobConfig = Action(parse.multipartFormData) {request =>
    request.body.file("config") map { file =>
      val uuid = UUID.randomUUID().toString
      val temp = new File(s"/tmp/$uuid")
      file.ref.moveTo(temp, replace = true)
      val tempPath = Paths.get(temp.getAbsolutePath)
      val configdata: String = Files.readAllLines(tempPath).toList.mkString("\n")
      if (ProcessorChain2.validateConf(configdata)) {        
        Ok(org.nlp4l.framework.views.html.newjob(configdata, "Successfully uploaded.", ""))
      } else {
        Files.delete(Paths.get(temp.getAbsolutePath))
        Ok(org.nlp4l.framework.views.html.newjob("", "", "Upload failed. May be an invalid config file?"))
      }
    } getOrElse {
      Ok(org.nlp4l.framework.views.html.newjob("", "", "Upload failed."))
    }
  }
  

  def saveJobInfo(jobId: Int) = Action.async(parse.json) { request =>
    val data = request.body
    val name = (data \ "name").as[String]
    val config = (data \ "config").as[String]

    if (name.isEmpty) {
      Future.successful(BadRequest("Job Name cannot be empty."))
    } else {
      val f: Future[Job] = jobDAO.get(jobId)
      Await.ready(f, scala.concurrent.duration.Duration.Inf)
      f.value.get match {
        case Success(job) => {
          jobDAO.update( Job(Some(jobId), name, config, job.lastRunId, job.lastRunAt, job.lastDeployAt) ) map {
            res => {
              val jsonResponse = Json.toJson(Job(Some(jobId), name, config, job.lastRunId, job.lastRunAt, job.lastDeployAt))
              Ok(jsonResponse)
            }
          } recover {
            case e => InternalServerError("Add failed. " + e.getMessage)
          }
        }
        case Failure(ex) => {
          jobDAO.insert(Job(None, name, config, 0, None, None)) map {
            res => {
              val jsonResponse = Json.toJson(res)
              Ok(jsonResponse)
            }
          } recover {
            case e => InternalServerError("Add failed. " + e.getMessage)
          }
        }
      }
      
    }
  }

  def deleteJob(jobId: Int) = Action.async {
    val f: Future[Job] = jobDAO.get(jobId)
    val job = Await.result(f, scala.concurrent.duration.Duration.Inf)
    jobDAO.delete(jobId) map {
      case (a) => {
        (1 to job.lastRunId).foreach {runId =>
          runDAO.dropTable(jobId, runId) 
        }
        jobDAO.dropReplayTable(jobId)
        runDAO.deleteJobStatusByJobId(jobId)
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      }
    } recover {
      case e => InternalServerError("Delete failed. " + e.getMessage)
    }
  }
  
  def deleteRunResult(jobId: Int, runId: Int) = Action.async {
    runDAO.dropTable(jobId, runId) map {
      case (a) => {
        jobDAO.deleteReplayTable(jobId, runId)
        runDAO.deleteJobStatusByJobIdAndRunId(jobId, runId)
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      }
    } recover {
      case e => InternalServerError("Delete failed. " + e.getMessage)
    }
  }
  
  def deleteRecord(jobId: Int, runId: Int, recordId: Int) = Action.async {
    val hashcode: Int= runDAO.fetchRecordHashcode(jobId, runId, recordId)
    runDAO.deleteRecord(jobId, runId, recordId) map {
      case (a) => {
        jobDAO.deleteOldReplay(jobId, runId, hashcode)
        val replay = Replay(runId, hashcode, "DEL", 0)
        jobDAO.insertReplay(jobId, replay)
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      }
    } recover {
      case e => Ok(Json.toJson(ActionResult(false, Seq(e.getMessage))))
    }
  }
  
  
  def addRecord(jobId: Int, runId: Int) = Action.async {implicit request =>
    val f: Future[Job] = jobDAO.get(jobId)
    val job = Await.result(f, scala.concurrent.duration.Duration.Inf)
    val dicAttr: DictionaryAttribute = new ProcessorChain2Builder().dicBuild(job.config)
    var cellList: Seq[Cell] = Seq()
    
    val formData: Map[String, Seq[String]]  = request.body.asFormUrlEncoded.getOrElse(Map())
    dicAttr.cellAttributeList foreach { c: CellAttribute =>
      val v = formData.get(c.name.toLowerCase()) map { list: Seq[String] =>
        val x:String = list.head
        if(x.length() > 0) {
          c.cellType match {
              case CellType.StringType => x.toString
              case CellType.IntType => c.toInt(x)
              case CellType.FloatType => c.toFloat(x)
              case CellType.DoubleType => c.toDouble(x)
              case CellType.DateType => c.toDate(x)
            }
        } else {
          null
        }
      }
      cellList = cellList :+ Cell(c.name.toLowerCase(), v.getOrElse(null))
    }
    val r = Record(cellList)
    val hashcode: Int = r.hashCode
    

    
    runDAO.addRecord(jobId, runId, dicAttr, r) map {
      case (recordId) => {
        val hashcode = runDAO.fetchRecordHashcode(jobId, runId, recordId)
        val replay = Replay(runId, hashcode, "ADD", 0)
        jobDAO.insertReplay(jobId, replay)
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      }
    } recover {
      case e => Ok(Json.toJson(ActionResult(false, Seq(e.getMessage))))
    }
  }
  
  
  def fetchRecord(jobId: Int, runId: Int, recordId: Int) = Action {
    val recordMap = runDAO.fetchRecordData(jobId, runId, recordId)
    val d = for {m <- recordMap} yield { 
      if(m._2 != null) { 
        m._1 -> m._2.toString
      } else { 
        m._1 -> null
      }
    }
    val jsonResponse = Json.obj(
          "record" -> Json.toJson(d)
        )
        Ok(jsonResponse)
  }
  
  
  def updateRecord(jobId: Int, runId: Int, recordId: Int) = Action {implicit request =>
    val f: Future[Job] = jobDAO.get(jobId)
    val job = Await.result(f, scala.concurrent.duration.Duration.Inf)
    val dicAttr: DictionaryAttribute = new ProcessorChain2Builder().dicBuild(job.config)
    var cellList: Seq[Cell] = Seq()
    var oldCellList: Seq[Cell] = Seq()
    
    val formData: Map[String, Seq[String]]  = request.body.asFormUrlEncoded.getOrElse(Map())
    val oldRecordMap = runDAO.fetchRecordData(jobId, runId, recordId)
    val d = for {m <- oldRecordMap} yield { 
      if(m._2 != null) { 
        m._1 -> m._2.toString
      } else { 
        m._1 -> null
      }
    }
    
    dicAttr.cellAttributeList foreach { c: CellAttribute =>
      val v = formData.get(c.name.toLowerCase()) map { list: Seq[String] =>
        val x:String = list.head
        c.cellType match {
            case CellType.StringType => x.toString
            case CellType.IntType => x.toInt
            case CellType.FloatType => x.toFloat
            case CellType.DoubleType => x.toDouble
            case CellType.DateType => DateTime.parse(x)
          }
      }
      cellList = cellList :+ Cell(c.name.toLowerCase(), v.getOrElse(null))
      
      val vv = d.get(c.name.toLowerCase()) map { x =>
        if(x != null) {
          c.cellType match {
              case CellType.StringType => x.toString
              case CellType.IntType => x.toInt
              case CellType.FloatType => x.toFloat
              case CellType.DoubleType => x.toDouble
              case CellType.DateType => DateTime.parse(x)
            }
        }
      }
      oldCellList = oldCellList :+ Cell(c.name.toLowerCase(), vv.getOrElse(null))
    }
    val newr = Record(cellList)
    val oldr = Record(oldCellList)
    
    runDAO.updateRecord(jobId, runId, recordId, dicAttr, newr) map {
      case (a) => {
        formData.get("hashcode") map { h =>
          val hashcode: Int = h.head.toInt
          val replay = Replay(runId, hashcode, "MOD", newr.hashCode)
          jobDAO.insertReplay(jobId, replay)
        }
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      }
    } recover {
      case e => InternalServerError("Add failed. " + e.getMessage)
    }
        
    Ok(Json.toJson(ActionResult(true, Seq("success"))))
  }
  
  def jobExists(jobId: Int): Future[Boolean] = {
    jobDAO.get(jobId).map(job => true ).recover {
      case ex: TimeoutException => false
      case _ => false
    }
  }

  def exec(jobId: Int) = Action.async {
    val f: Future[Job] = jobDAO.get(jobId)
    Await.ready(f, scala.concurrent.duration.Duration.Inf)
    f.value.get match {
      case Success(job) => {
        processActor.tell(JobMessage(jobId), null)
        Future.successful( Ok(s"Executing Job '$job.name'...") )
      }
      case Failure(ex) => Future.successful(NotFound(org.nlp4l.framework.views.html.notFound("Job not found")))
    }
  }
  
  
  def validateResult(jobId: Int, runId: Int) = Action {
    try {

      val dic = runDAO.fetchAll(jobId, runId)
      val chain = ValidatorChain.getChain(jobDAO, jobId)
      val errMsg = chain.process(dic)
      
      if(errMsg.isEmpty) {
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      } else {
        Ok(Json.toJson(ActionResult(false, errMsg)))
      }
    } catch {
      case e: Exception => Ok(Json.toJson(ActionResult(false, Seq(e.getMessage))))
    }
  }
  
  
  def deployResult(jobId: Int, runId: Int) = Action {
    try {

      val dic = runDAO.fetchAll(jobId, runId)
      val chain = DeployerChain.getChain(jobDAO, jobId)
      val errMsg = chain.process(jobDAO, jobId, runId, dic)
      
      if(errMsg.isEmpty) {
        Ok(Json.toJson(ActionResult(true, Seq("success"))))
      } else {
        Ok(Json.toJson(ActionResult(false, errMsg)))
      }

    } catch {
      case e: Exception => Ok(Json.toJson(ActionResult(false, Seq(e.getMessage))))
    }
  }
  
  
  def jobStatus() = Action.async {request =>
    val offset = request.getQueryString("offset") match {
      case Some(x) if x != "" => x.toInt
      case _ => 0
    }
    val size = request.getQueryString("limit") match {
      case Some(x) => x.toInt
      case _ => 10
    }
    val sort = request.getQueryString("sort") match {
      case Some(c) => c
      case _ => "id"
    }
    val order = request.getQueryString("order") match {
      case Some(c) => c
      case _ => "desc"
    }
    var total = 0
    runDAO.fetchAllJobStatus().map {
      res =>
        total = res.size
    }
    runDAO.fetchJobStatus("id", "desc", offset, size).map {
      res =>
        val jsonResponse = Json.obj(
          "total" -> total,
          "rows" -> Json.toJson(res)
        )
        Ok(jsonResponse)
    }
  }
  
  
  def jobResult(jobId: Int, runId: Int) = Action { request =>
    val tableName = s"run_${jobId}_${runId}"
    
    val offset = request.getQueryString("offset") match {
      case Some(x) if x != "" => x.toInt
      case _ => 0
    }
    val size = request.getQueryString("limit") match {
      case Some(x) => x.toInt
      case _ => 10
    }
    val sort = request.getQueryString("sort") match {
      case Some(c) => c
      case _ => "id"
    }
    val order = request.getQueryString("order") match {
      case Some(c) => c
      case _ => "asc"
    }
    var total = 0
    runDAO.totalCount(jobId, runId).map {
      res =>
        total = res
    }
    
    val f: Future[Job] = jobDAO.get(jobId)
    val job = Await.result(f, scala.concurrent.duration.Duration.Inf)
    val dic: DictionaryAttribute = new ProcessorChain2Builder().dicBuild(job.config)
    
    val res:Dictionary = runDAO.fetch(tableName, job, dic, sort, order, offset, size)
    val res2 = res.recordList.map { x:Record => RecordWithAttrbute(x, dic) }
      
    val jsonResponse = Json.obj(
      "total" -> total,
      "rows" -> Json.toJson(res2)
    )
    Ok(jsonResponse)
    
  }
  
  def exportResult(jobId: Int, runId: Int) = Action {
    try {
      val f: Future[Job] = jobDAO.get(jobId)
      val job = Await.result(f, scala.concurrent.duration.Duration.Inf)
      val dic: DictionaryAttribute = new ProcessorChain2Builder().dicBuild(job.config)
      
      val res:Dictionary = runDAO.fetchAllColumn(jobId, runId)
      val res2 = res.recordList.map { x:Record => RecordWithAttrbute(x, dic) }
      
      val file = new File(s"run_${jobId}_${runId}.json")
      val fileWriter:FileWriter = new FileWriter(file, false);
      val printWriter:PrintWriter = new PrintWriter(new BufferedWriter(fileWriter));
      printWriter.print(Json.toJson(res2));
      printWriter.close();
      Ok.sendFile(file)

    } catch {
      case e: Exception => Ok(Json.toJson(ActionResult(false, Seq(e.getMessage))))
    }
  }
  
  
}