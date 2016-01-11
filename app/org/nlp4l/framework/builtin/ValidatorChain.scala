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

package org.nlp4l.framework.builtin

import scala.collection.mutable
import scala.concurrent.Await
import scala.collection.convert.WrapAsScala._

import org.nlp4l.framework.dao.JobDAO
import org.nlp4l.framework.models.Dictionary
import org.nlp4l.framework.processors.Validator
import org.nlp4l.framework.processors.ValidatorFactory

import com.typesafe.config.ConfigFactory

import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ValidatorChain (val chain: List[Validator]) {
  private val logger = Logger(this.getClass)
  
  def process(dic: Dictionary): Seq[String] = {
    var errMsg: Seq[String] = Seq()
    def loop(li: List[Validator], data:Option[Dictionary] = None): Unit = li match {
      case Nil => ()
      case head :: Nil =>
        val out: Tuple2[Boolean, Seq[String]] = head.validate(data)
        if(!out._1) errMsg = errMsg union out._2
      case head :: tail =>
        val out: Tuple2[Boolean, Seq[String]] = head.validate(data)
        if(!out._1) errMsg = errMsg union out._2
        loop(tail, data)
    }
    loop(chain, Some(dic))
    errMsg
  }
}

object ValidatorChain {

  // Processor
  private var mapP: Map[Int, ValidatorChain] = null
  def chainMap: Map[Int, ValidatorChain] = mapP
  
  def loadChain(jobDAO: JobDAO, jobId: Int): Unit = {
    jobDAO.get(jobId).map(
        job => 
           mapP += (jobId -> new ValidatorChainBuilder().build(job.config).result())
    )
  }
  
  def getChain(jobDAO: JobDAO, jobId: Int): ValidatorChain = {
    val job = Await.result(jobDAO.get(jobId), scala.concurrent.duration.Duration.Inf)
    new ValidatorChainBuilder().build(job.config).result()
  }
}


class ValidatorChainBuilder() {
  val logger = Logger(this.getClass)
  val buf = mutable.ArrayBuffer[Validator]()

  def build(confStr: String): ValidatorChainBuilder = {
    val config = ConfigFactory.parseString(confStr)

    var gSettings: Map[String, Object] = Map()
    if(config.hasPath("settings")) {
      gSettings = config.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped()).toMap
    }
    
    val v = config.getConfigList("validators")
    v.foreach {
      pConf =>
        try {
          val className = pConf.getString("class")
          val constructor = Class.forName(className).getConstructor(classOf[Map[String, String]])
          var lSettings: Map[String, Object] = Map()
          if(pConf.hasPath("settings")) {
            lSettings = pConf.getConfig("settings").entrySet().map(f => f.getKey -> f.getValue.unwrapped()).toMap
          }
          val settings = gSettings ++ lSettings
          val facP = constructor.newInstance(settings).asInstanceOf[ValidatorFactory]
          val p:Validator = facP.getInstance()
          buf += p
        } catch {
          case e: Exception => logger.error(e.getMessage)
        }
    }
    this
  }

  def result() = new ValidatorChain(buf.toList)
}
