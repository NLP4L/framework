/*
 * Copyright 2016 org.NLP4L
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

import java.io.FileNotFoundException

import org.nlp4l.framework.models._
import org.specs2.mutable.Specification

class FeaturedProcessorsSpec extends Specification {

  "TextRecordsProcessor" should {
    "read solr.log file" in {
      val settings = Map("file" -> "test/resources/org/nlp4l/framework/builtin/solr.log", "encoding" -> "UTF-8")
      val processor = new TextRecordsProcessorFactory(settings).getInstance
      val result: Dictionary = processor.execute(None).get
      result must_!= None
      result.recordList.size must_==(188)
    }

    "throw a FileNotFoundException" in {
      val settings = Map("file" -> "test/resources/org/nlp4l/framework/builtin/no_such_file", "encoding" -> "UTF-8")
      val processor = new TextRecordsProcessorFactory(settings).getInstance
      processor.execute(None) must throwA[FileNotFoundException]
    }
  }

  val solrLogProc = new StandardSolrQueryLogProcessorFactory(Map()).getInstance

  "StandardSolrQueryLogProcessor" should {
    "ignore records that don't match the pattern" in {
      solrLogProc.execute(Some(logRecord("aaa"))) must_== None
      val log =
        """2016-01-18 06:26:24.397
          | INFO  (searcherExecutor-7-thread-1-processing-x:collection1)
          |  [   x:collection1] o.a.s.c.SolrCore [collection1]
          |  Registered new searcher Searcher@41c3efab[collection1]
          |  main{ExitableDirectoryReader(UninvertingDirectoryReader(Uninverting(_0(5.3.1):C32)))}""".stripMargin
      solrLogProc.execute(Some(logRecord(log))) must_== None
    }

    "get single parameters" in {
      val log =
        """2016-01-18 06:27:31.343 INFO  (qtp1359044626-35) [   x:collection1] o.a.s.c.S.Request [collection1] webapp=/solr path=/select params={q=ipod+mini&fq=cat:cable} hits=0 status=0 QTime=6""".stripMargin
      val result = solrLogProc.execute(Some(logRecord(log)))
      result must_!= None
      checkCell(result, "date", "2016-01-18 06:27:31")
      checkCell(result, "q", "ipod+mini")
      checkCell(result, "fq", "cat:cable")
      checkCell(result, "hits", 0)
      checkCell(result, "QTime", 6)
    }

    "get multiple parameters" in {
      val log =
        """2016-01-18 06:27:31.343 INFO  (qtp1359044626-35) [   x:collection1] o.a.s.c.S.Request [collection1] webapp=/solr path=/select params={q=ipod+mini&fq=cat:cable&fq=price:[1 TO 100]&facet.field=cat&facet.field=author} hits=1000 status=0 QTime=123""".stripMargin
      val result = solrLogProc.execute(Some(logRecord(log)))
      result must_!= None
      checkCell(result, "date", "2016-01-18 06:27:31")
      checkCell(result, "q", "ipod+mini")
      checkCell(result, "fq", "cat:cable,price:[1 TO 100]")
      checkCell(result, "facet.field", "cat,author")
      checkCell(result, "hits", 1000)
      checkCell(result, "QTime", 123)
    }
  }

  def logRecord(s: String) = Record(Seq(Cell("text", s)))

  def checkCell(r: Option[Record], n: String, v: Any): Boolean = {
    if(r == None) false
    else checkCell(r.get, n, v)
  }

  def checkCell(r: Record, n: String, v: Any): Boolean = {
    val value = r.cellValue(n)
    if(value == None) false
    else value.get must_== v
  }
}