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

import java.net.URLEncoder

import org.nlp4l.framework.models.{CellAttribute, CellType}

class StandardSolrSearchCellAttribute(val searchOn: String, collection: String, idField: String, hlField: String,
                                      name: String, cellType: CellType, isEditable: Boolean, isSortable: Boolean, userDefinedHashCode:(Any) => Int = null)
  extends CellAttribute(name, cellType, isEditable, isSortable, userDefinedHashCode) {
  override def format(cell: Any): String = {
    val query = cell.toString
    val url = URLEncoder.encode(s"$searchOn", "UTF-8")
    val encodedQuery = URLEncoder.encode(s"$query", "UTF-8")
    hlField match {
      case null => s"""<a href="/search/solr/$url/$collection/$encodedQuery?id=$idField">$query</a>"""
      case _ => s"""<a href="/search/solr/$url/$collection/$encodedQuery?id=$idField&hl=$hlField">$query</a>"""
    }
  }
}
