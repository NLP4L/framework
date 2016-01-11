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

package org.nlp4l.framework.processors

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable
import scala.concurrent.Await
import scala.util.{ Try, Success, Failure }
import scala.collection.convert.WrapAsScala._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.joda.time.DateTime
import org.nlp4l.framework.dao.JobDAO
import org.nlp4l.framework.dao.RunDAO
import org.nlp4l.framework.models.Dictionary
import org.nlp4l.framework.models.DictionaryAttribute
import org.nlp4l.framework.models.Record
import org.nlp4l.framework.builtin.ReplayProcessor
import org.nlp4l.framework.builtin.WrapProcessor
import org.nlp4l.framework.builtin.SortProcessor
import org.nlp4l.framework.builtin.MergeProcessor
import org.nlp4l.framework.builtin.Constants
import org.nlp4l.framework.builtin.JobStatus
import org.nlp4l.framework.builtin.Job


class ProcessorChain (val chain: List[Processor]) {
  private val logger = Logger(this.getClass)

  def process(jobDAO: JobDAO, runDAO: RunDAO, jobId: Int, dicAttr: DictionaryAttribute) = {
    val job = Await.result(jobDAO.get(jobId), scala.concurrent.duration.Duration.Inf)
    val runId = job.lastRunId + 1
    jobDAO.update(Job(job.jobId, job.name, job.config, runId, Some(new DateTime()), job.lastDeployAt))
    def loop(li: List[Processor], js: JobStatus, data:Option[Dictionary] = None): Unit = {
      try {
        li match {
          case Nil => ()
          case head :: Nil =>
            var out: Option[Dictionary] = head.execute(data)
            val cname = head.asInstanceOf[AnyRef].getClass.getName
            if(cname == Constants.SORTPROCESSOR_CLASS) {
              out = head.asInstanceOf[SortProcessor].sort(jobDAO, runDAO, jobId, runId, dicAttr, out)
            } else if(cname == Constants.REPLAYPROCESSOR_CLASS) {
              out = head.asInstanceOf[ReplayProcessor].replay(jobDAO, runDAO, jobId, dicAttr, out)
            } else if(cname == Constants.MERGEPROCESSOR_CLASS) {
              out = head.asInstanceOf[MergeProcessor].merge(dicAttr, out)
            }
            runDAO.updateJobStatus(JobStatus(js.id, js.jobId, js.runId, js.total, js.total-li.size+1))
            ProcessorChain.outputResult(jobDAO, runDAO, jobId, runId, dicAttr, out)
          case head :: tail =>
            var out:Option[Dictionary]  = head.execute(data)
            val cname = head.asInstanceOf[AnyRef].getClass.getName
            if(cname == Constants.SORTPROCESSOR_CLASS) {
              out = head.asInstanceOf[SortProcessor].sort(jobDAO, runDAO, jobId, runId, dicAttr, out)
            } else if(cname == Constants.REPLAYPROCESSOR_CLASS) {
              out = head.asInstanceOf[ReplayProcessor].replay(jobDAO, runDAO, jobId, dicAttr, out)
            } else if(cname == Constants.MERGEPROCESSOR_CLASS) {
              out = head.asInstanceOf[MergeProcessor].merge(dicAttr, out)
            }
            val newjs = JobStatus(js.id, js.jobId, js.runId, js.total, js.total-li.size+1)
            runDAO.updateJobStatus(newjs)
            loop(tail, newjs, out)
        }
      } catch {
        case e: Exception => {
          val errjs = JobStatus(js.id, js.jobId, js.runId, js.total, js.done, e.getMessage)
          runDAO.updateJobStatus(errjs)
          logger.error(e.getMessage)
        }
      }
    }
    val js = JobStatus(None, jobId, runId, chain.size, 0)
    runDAO.insertJobStatus(js) map {newjs =>
          loop(chain, newjs)
    }
  }
}

object ProcessorChain {
  private val logger = Logger(this.getClass)
  
  // Processor
  private var mapP: Map[Int, ProcessorChain] = Map()
  def chainMap: Map[Int, ProcessorChain] = mapP
  
  // DictionaryAttribute
  private var mapD: Map[Int, DictionaryAttribute] = Map()
  def dicMap: Map[Int, DictionaryAttribute] = mapD
  
  def loadChain(jobDAO: JobDAO, jobId: Int): Unit = {
    jobDAO.get(jobId).map(
        job => {
          val pcb = new ProcessorChainBuilder()
           mapP += (jobId -> pcb.procBuild(jobId, job.config).result())
           val dicAttr = pcb.dicBuild(job.config)

           // Replay data
           var addedRecordList: Map[Int, Record] = Map()
           var modifiedRecordList: Map[Int, Record] = Map()
           val aa = jobDAO.fetchReplayOfAdd(jobId) 
           jobDAO.fetchReplayOfAdd(jobId) foreach { hd: (Int, Int) =>
             val runId: Int = hd._1
             val hashcode: Int = hd._2
             jobDAO.fetchRecordByHashcode(jobId, runId, hashcode) map { rec: Record =>
               addedRecordList += (hashcode -> rec)
             }
           }
           val modifiedList: List[(Int, Int, Int)] = jobDAO.fetchReplayOfMod(jobId)
           jobDAO.fetchReplayOfMod(jobId) foreach { hd: (Int, Int, Int) =>
             val runId: Int = hd._1
             val hashcode: Int = hd._2
             val modToHashcode: Int = hd._3
             jobDAO.fetchRecordByHashcode(jobId, runId, modToHashcode) map { rec: Record =>
               modifiedRecordList += (hashcode -> rec)
             }
           }
           dicAttr.addedRecordList = addedRecordList
           dicAttr.modifiedRecordList = modifiedRecordList
           dicAttr.deletedRecordList = jobDAO.fetchReplayOfDel(jobId)

           mapD += (jobId -> dicAttr)
        }
    )
  }
  
  
  def getChain(jobDAO: JobDAO, runDAO: RunDAO, jobId: Int): ProcessorChain = {
    val job = Await.result(jobDAO.get(jobId), scala.concurrent.duration.Duration.Inf)
    try {
      val pc = new ProcessorChainBuilder().procBuild(jobId, job.config).result()
      pc
    } catch {
      case e: Exception => {
        val runId = job.lastRunId + 1
        val errjs = JobStatus(None, jobId, runId, 0, 0, e.getMessage)
        runDAO.insertJobStatus(errjs)
        logger.error(e.getMessage)
        throw e
      }
    }
  }
  
  def getDictionaryAttribute(jobDAO: JobDAO, jobId: Int): DictionaryAttribute = {
    val job = Await.result(jobDAO.get(jobId), scala.concurrent.duration.Duration.Inf)
    val pcb = new ProcessorChainBuilder()
    val dicAttr = pcb.dicBuild(job.config)

     // Replay data
     var addedRecordList: Map[Int, Record] = Map()
     var modifiedRecordList: Map[Int, Record] = Map()
     jobDAO.fetchReplayOfAdd(jobId) foreach { hd: (Int, Int) =>
       val runId: Int = hd._1
       val hashcode: Int = hd._2
       jobDAO.fetchRecordByHashcode(jobId, runId, hashcode) map { rec: Record =>
         addedRecordList += (hashcode -> rec)
       }
     }
     val modifiedList: List[(Int, Int, Int)] = jobDAO.fetchReplayOfMod(jobId)
     jobDAO.fetchReplayOfMod(jobId) foreach { hd: (Int, Int, Int) =>
       val runId: Int = hd._1
       val hashcode: Int = hd._2
       val modToHashcode: Int = hd._3
       jobDAO.fetchRecordByHashcode(jobId, runId, modToHashcode) map { rec: Record =>
         modifiedRecordList += (hashcode -> rec)
       }
     }
     dicAttr.addedRecordList = addedRecordList
     dicAttr.modifiedRecordList = modifiedRecordList
     dicAttr.deletedRecordList = jobDAO.fetchReplayOfDel(jobId)

     dicAttr
  }
  
  /**
   * Save the Dictionary to database
   */
  def outputResult(jobDAO: JobDAO, runDAO: RunDAO, jobId: Int, runId: Int, dicAttr: DictionaryAttribute, dic: Option[Dictionary]): Unit = {
    jobDAO.get(jobId) map {job: Job =>
      dic map { d =>
        val f1 = runDAO.dropTable(jobId, runId)
        Await.ready(f1, scala.concurrent.duration.Duration.Inf)
        f1.value.get match {
          case Success(n) => n
          case Failure(ex) => logger.debug(ex.getMessage)
        }
        val f2 = runDAO.createTable(jobId, runId, dicAttr)
        Await.ready(f2, scala.concurrent.duration.Duration.Inf)
        f2.value.get match {
          case Success(n) => runDAO.insertData(jobId, runId, dicAttr, d)
          case Failure(ex) => throw(ex)
        }
      }
    }
  }

  /**
   * Validate the uploaded job config file
   */
  def validateConf(confStr: String): Boolean = {
    try {
      val config = ConfigFactory.parseString(confStr, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
      if (!config.hasPath("dictionary") || !config.hasPath("processors") || !config.hasPath("deployers")) false
      else {
        val b1 = config.getConfigList("dictionary").toList.forall {
          pConf => pConf.hasPath("class")
        }
        val b2 = config.getConfigList("processors").toList.forall {
          pConf => pConf.hasPath("class")
        }
        val b3 = config.getConfigList("deployers").toList.forall {
          pConf => pConf.hasPath("class")
        }
        val b4 =
          if (!config.hasPath("validators")) true
          else {
            config.getConfigList("validators").toList.forall {
              pConf => pConf.hasPath("class")
            }
          }
        b1 && b2 && b3 && b4
      }
    } catch {
      case e: Exception => {
        logger.error(e.getMessage)
        false
      }
    }
  }
}

class ProcessorChainBuilder() {
  val logger = Logger(this.getClass)
  val buf = mutable.ArrayBuffer[Processor]()

  def procBuild(jobId: Int, confStr: String): ProcessorChainBuilder = {
    val config = ConfigFactory.parseString(confStr)
    
    var gSettings: Map[String, String] = Map()
    if(config.hasPath("settings")) {
      gSettings = config.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped().toString()).toMap
    }

    config.getConfigList("processors").foreach {
      pConf =>
          val className = pConf.getString("class")
          if(className == Constants.WRAPPROCESSOR_CLASS) {
            buf += wrapBuild(pConf)
          } else {
            val constructor = Class.forName(className).getConstructor(classOf[Map[String, String]])
            var lSettings: Map[String, String] = Map()
            if(pConf.hasPath("settings")) {
              lSettings = pConf.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped().toString()).toMap
            }
            val settings = gSettings ++ lSettings
            val facP = constructor.newInstance(settings).asInstanceOf[ProcessorFactory]
            val p = facP.getInstance()
            buf += p
          }
    }
    this
  }
  
  def dicBuild(confStr: String): DictionaryAttribute = {
    val config = ConfigFactory.parseString(confStr)
    
    var gSettings: Map[String, String] = Map()
    if(config.hasPath("settings")) {
      gSettings = config.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped().toString()).toMap
    }
    
    val pConf = config.getConfigList("dictionary").get(0)
    val className = pConf.getString("class")
    val constructor = Class.forName(className).getConstructor(classOf[Map[String, String]])
    var lSettings: Map[String, String] = Map()
    if(pConf.hasPath("settings")) {
      lSettings = pConf.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped().toString()).toMap
    }
    val settings = gSettings ++ lSettings
    val facP = constructor.newInstance(settings).asInstanceOf[DictionaryAttributeFactory]
    facP.getInstance()
  }
  
  def wrapBuild(wrapConf: Config): Processor = {
    var buf: Seq[RecordProcessor] = Seq()
    val pConf = wrapConf.getConfigList("recordProcessors").get(0)
    
    try {
      val className = pConf.getString("class")
      val constructor = Class.forName(className).getConstructor(classOf[Map[String, String]])
      var settings: Map[String, String] = Map()
      if(pConf.hasPath("settings")) {
        settings = pConf.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped().toString()).toMap
      }
      val facP = constructor.newInstance(settings).asInstanceOf[RecordProcessorFactory]
      val p = facP.getInstance()
      buf = buf :+ p
    } catch {
      case e: Exception => logger.error(e.getMessage)
    }
 
    val className = Constants.WRAPPROCESSOR_CLASS
    val constructor = Class.forName(className).getConstructor(classOf[Seq[RecordProcessor]])
    constructor.newInstance(buf).asInstanceOf[WrapProcessor]
  }

  def result() = new ProcessorChain(buf.toList)
}
