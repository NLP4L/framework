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

package org.nlp4l.framework.models

import java.util.Date

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

/**
 * Data unit
 */
case class Cell (
    name: String,
    value: Any
) {
  override def hashCode: Int = {
    val prime:Int = 31
    var result: Int = 1
    if(value == null) {
      result = prime * result + 0
    } else {
      result = prime * result + value.toString().hashCode
    }
    result
  }
  
  /**
   * Merge two cell with glue string when cell value is string
   */
  def merge(that: Cell, glue: String): Cell = {
    this.value match {
      case x: String => {
        (x, that.value) match {
          case (null, null) => Cell(this.name, null)
          case (null, y) => Cell(this.name, y.toString())
          case (x, null) => Cell(this.name, x)
          case (x, y) => Cell(this.name, this.value.toString() + glue + y.toString())
        }
      }
      case _ => this
    }
  }
}

/**
 * Collection of cell, such like row
 */
case class Record (
    cellList: Seq[Cell]
) {
  /**
   * Merge two record having same cell value of key with glue string 
   */
  def merge(key: String, glue:String, that:Record): Record = {
    var celllist: Seq[Cell] = Seq()
    this.cellList foreach { thisCell =>
      val thatCell = that.cellList.filter(_.name == thisCell.name).head
      if(thisCell.name == key) {
        celllist = celllist :+ thisCell
      } else {
        celllist = celllist :+ thisCell.merge(thatCell, glue)
      }
    }
    Record(celllist)
  }
  
  def canMerge(key: String, that:Record): Boolean = {
    val thisCell = this.cellList.filter(_.name == key).head
    val thatCell = that.cellList.filter(_.name == key).head
    thisCell.value == thatCell.value
  }
}

/**
 * Collection of Record
 * Input and output unit
 */
case class Dictionary (
    recordList: Seq[Record] 
)

trait CellView {
  /**
   * Format a cell value
   * This method called when cell value displayed by framework
   */
  def format(cell: Any): String = {
    if(cell.isInstanceOf[DateTime]) {
      val dcell: DateTime = cell.asInstanceOf[DateTime]
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").print(dcell)
    } else {
      cell.toString()
    }
  }

  /**
   * Convert inputed data to 
   */
  def toInt(edit: String): Int = {
    edit.toInt
  }
  def toFloat(edit: String): Float = {
    edit.toFloat
  }
  def toDouble(edit: String): Double = {
    edit.toDouble
  }
  def toDate(edit: String): Date = {
    DateTime.parse(edit).toDate()
  }
}

case class CellAttribute(
    val name: String,
    val cellType: CellType, // String, Int, Float, Double, DateTime
    val isEditable: Boolean = false,
    val isSortable: Boolean = false
    ) extends CellView {
  
  def columnName: String = {
    name.toLowerCase()
  }
}

case class RecordWithAttrbute(record: Record, attribute: DictionaryAttribute)


/**
 * Data type enum for Cell 
 */
object CellType {
  case object StringType extends CellType(0)
  case object IntType extends CellType(1)
  case object FloatType extends CellType(2)
  case object DoubleType extends CellType(3)
  case object DateType extends CellType(4)
}
sealed abstract class CellType(val code: Int) {
  val name = toString
}


class DictionaryAttribute(val name: String, val cellAttributeList: Seq[CellAttribute]) {

  // These lists are for Replay Processor
  var addedRecordList: Map[Int, Record] = Map()      // New added record hashCode and record data
  var deletedRecordList: List[Int] = List()      // Deleted record hashCode
  var modifiedRecordList: Map[Int, Record] = Map()      // Original record hashCode and modified record data 
  
  def getCellAttribute(name: String): Option[CellAttribute] = {
    cellAttributeList.filter(_.name.toLowerCase() == name.toLowerCase()).headOption
  }
}


/**
 * Message class between actors related job execution
 */
case class JobMessage(jobId: Int)

/**
 * Response of action
 */
case class ActionResult(status:Boolean, message:Seq[String])

object Constants {
  val WRAPPROCESSOR_CLASS = "org.nlp4l.framework.processors.WrapProcessor"
  val REPLAYPROCESSORFACTORY_CLASS = "org.nlp4l.framework.processors.ReplayProcessorFactory"
  val SORTPROCESSOR_CLASS = "org.nlp4l.framework.processors.SortProcessor"
  val MERGEPROCESSOR_CLASS = "org.nlp4l.framework.processors.MergeProcessor"
  val REPLAYPROCESSOR_CLASS = "org.nlp4l.framework.processors.ReplayProcessor"
}


