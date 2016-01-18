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

import java.io.File

import org.nlp4l.framework.models.{Cell, Record, Dictionary}
import org.nlp4l.framework.processors.{Processor, ProcessorFactory}

import scala.io.Source

class TextRecordsProcessorFactory(settings: Map[String, String]) extends ProcessorFactory(settings) {
  override def getInstance: Processor = {
    new TextRecordsProcessor(getStrParamRequired("file"), settings.getOrElse("encoding", "UTF-8"))
  }
}

class TextRecordsProcessor(val file: String, val encoding: String) extends Processor {
  override def execute(data: Option[Dictionary]): Option[Dictionary] = {
    val ff = new File(file).getAbsoluteFile
    val f = Source.fromFile(ff, encoding)
    try {
      Some(Dictionary(f.getLines().map(a => Record(Seq(Cell("text", a)))).toList))
    }
    finally {
      f.close()
    }
  }
}
