/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.index

import java.io.FileNotFoundException

import scala.annotation.tailrec
import scala.collection.mutable.{HashMap, ListBuffer}

import com.fasterxml.jackson.annotation.JsonIgnore
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path, PathFilter}
import org.apache.spark.sql.types.{DataType, StructType}

import com.microsoft.hyperspace.actions.Constants
import com.microsoft.hyperspace.util.PathUtils

// IndexLogEntry-specific fingerprint to be temporarily used where fingerprint is not defined.
case class NoOpFingerprint() {
  val kind: String = "NoOp"
  val properties: Map[String, String] = Map()
}

// IndexLogEntry-specific Content that uses IndexLogEntry-specific fingerprint.
case class Content(root: Directory, fingerprint: NoOpFingerprint = NoOpFingerprint()) {
  // List of fully qualified paths of all files mentioned in this Content object.
  @JsonIgnore
  lazy val files: Seq[Path] = {
    // Recursively find files from directory tree.
    def rec(prefixPath: Path, directory: Directory): Seq[Path] = {
      val files = directory.files.map(f => new Path(prefixPath, f.name))
      files ++ directory.subDirs.flatMap { dir =>
        rec(new Path(prefixPath, dir.name), dir)
      }
    }

    rec(new Path(root.name), root)
  }
}

object Content {

  /**
   * Create a Content object from a directory path by recursively listing its leaf files. All
   * files from the directory tree will be part of the Directory.
   *
   * @param path Starting directory path under which the files will be considered part of the
   *             Directory object.
   * @param pathFilter Filter for accepting paths. The default filter is picked from spark
   *                   codebase, which filters out files like _SUCCESS.
   * @param throwIfNotExists Throws FileNotFoundException if path is not found. Else creates a
   *                         blank directory tree with no files.
   * @return Content object with Directory tree starting at root, and containing all leaf files
   *         from "path" argument.
   */
  def fromDirectory(
      path: Path,
      pathFilter: PathFilter = PathUtils.DataPathFilter,
      throwIfNotExists: Boolean = false): Content =
    Content(Directory.fromDirectory(path, pathFilter, throwIfNotExists))

  /**
   * Create a Content object from a specified list of leaf files. Any files not listed here will
   * NOT be part of the returned object.
   *
   * @param files List of leaf files.
   * @return Content object with Directory tree from leaf files.
   */
  def fromLeafFiles(files: Seq[FileStatus]): Content = Content(Directory.fromLeafFiles(files))
}

case class Directory(name: String, files: Seq[FileInfo] = Seq(), subDirs: Seq[Directory] = Seq())

object Directory {

  /**
   * Create a Directory object from a directory path by recursively listing its leaf files. All
   * files from the directory tree will be part of the Directory.
   *
   * If the directory doesn't exist on file system, it will either throw an exception if
   * throwIfNotExists flag is set. Otherwise, this will create an empty Directory object
   * starting at the root, ending at the directory path specified.
   *
   * @param path Starting directory path under which the files will be considered part of the
   *             Directory object.
   * @param pathFilter Filter for accepting paths. The default filter is picked from spark
   *                   codebase, which filters out files like _SUCCESS.
   * @param throwIfNotExists If true, throw FileNotFoundException if path is not found. If set to
   *                         false, create a blank directory tree with no files.
   * @return Directory tree starting at root, and containing the files from "path" argument.
   */
  def fromDirectory(
      path: Path,
      pathFilter: PathFilter = PathUtils.DataPathFilter,
      throwIfNotExists: Boolean = false): Directory = {
    val fs = path.getFileSystem(new Configuration)
    val leafFiles = listLeafFiles(path, pathFilter, throwIfNotExists, fs)

    if (leafFiles.nonEmpty) {
      fromLeafFiles(leafFiles)
    } else {
      // leafFiles is empty either because the directory doesn't exist on disk or this directory
      // and all its subdirectories, if present, are empty. In both cases, create an empty
      // directory object.
      createEmptyDirectory(path)
    }
  }

  @tailrec
  private def createEmptyDirectory(path: Path, subDirs: Seq[Directory] = Seq()): Directory = {
    if (path.isRoot) {
      Directory(path.toString, subDirs = subDirs)
    } else {
      createEmptyDirectory(path.getParent, Seq(Directory(path.getName, subDirs = subDirs)))
    }
  }

  /**
   * Create a Content object from a specified list of leaf files. Any files not listed here will
   * NOT be part of the returned object
   * Pre-requisite: files list should be non-empty.
   * Pre-requisite: all files must be leaf files.
   *
   * @param files List of leaf files.
   * @return Content object with Directory tree from leaf files.
   */
  def fromLeafFiles(files: Seq[FileStatus]): Directory = {
    require(
      files.nonEmpty,
      s"Empty files list found while creating a ${Directory.getClass.getName}.")

    require(
      files.forall(!_.isDirectory),
      "All files must be leaf files for creation of Directory.")

    /* from org.apache.spark.sql.execution.datasources.InMemoryFileIndex. */
    val leafDirToChildrenFiles = files.toArray.groupBy(_.getPath.getParent)

    // Hashmap from directory path to Directory object, used below for quick access from path.
    val pathToDirectory = HashMap[Path, Directory]()

    for ((dirPath, files) <- leafDirToChildrenFiles) {
      val allFiles = ListBuffer[FileInfo]()
      allFiles.appendAll(files.map(FileInfo(_)))

      if (pathToDirectory.contains(dirPath)) {
        // Map already contains this directory. Just append the files to its existing list.
        pathToDirectory(dirPath).files.asInstanceOf[ListBuffer[FileInfo]].appendAll(allFiles)
      } else {
        var curDirPath = dirPath
        // Create a new Directory object and add it to Map
        val subDirs = ListBuffer[Directory]()
        var directory = Directory(curDirPath.getName, files = allFiles, subDirs = subDirs)
        pathToDirectory.put(curDirPath, directory)

        // Keep creating parent Directory objects and add to the map if non-existing.
        while (curDirPath.getParent != null && !pathToDirectory.contains(curDirPath.getParent)) {
          curDirPath = curDirPath.getParent

          directory = Directory(
            if (curDirPath.isRoot) curDirPath.toString else curDirPath.getName,
            subDirs = ListBuffer(directory),
            files = ListBuffer[FileInfo]())

          pathToDirectory.put(curDirPath, directory)
        }

        // Either root is reached (parent == null) or an existing directory is found. If it's the
        // latter, add the newly created directory tree to its subDirs.
        if (curDirPath.getParent != null) {
          pathToDirectory(curDirPath.getParent).subDirs
            .asInstanceOf[ListBuffer[Directory]]
            .append(directory)
        }
      }
    }

    pathToDirectory(getRoot(files.head.getPath))
  }

  // Return file system root path from any path. E.g. "file:/C:/a/b/c" will have root "file:/C:/".
  // For linux systems, this root will be "file:/". Other hdfs compatible file systems will have
  // corresponding roots.
  @tailrec
  private def getRoot(path: Path): Path = {
    if (path.isRoot) path else getRoot(path.getParent)
  }

  private def listLeafFiles(
      path: Path,
      pathFilter: PathFilter = PathUtils.DataPathFilter,
      throwIfNotExists: Boolean = false,
      fs: FileSystem): Seq[FileStatus] = {
    try {
      val (files, directories) = fs.listStatus(path).partition(_.isFile)
      // TODO: explore fs.listFiles(recursive = true) for better performance of file listing.
      files.filter(s => pathFilter.accept(s.getPath)) ++
        directories.flatMap(d => listLeafFiles(d.getPath, pathFilter, throwIfNotExists, fs))
    } catch {
      case _: FileNotFoundException if !throwIfNotExists => Seq()
      case e: Throwable => throw e
    }
  }
}

// modifiedTime is an Epoch time in milliseconds. (ms since 1970-01-01T00:00:00.000 UTC).
case class FileInfo(name: String, size: Long, modifiedTime: Long)

object FileInfo {
  def apply(s: FileStatus): FileInfo = {
    require(s.isFile, s"${FileInfo.getClass.getName} is applicable for files, not directories.")
    FileInfo(s.getPath.getName, s.getLen, s.getModificationTime)
  }
}

// IndexLogEntry-specific CoveringIndex that represents derived dataset.
case class CoveringIndex(properties: CoveringIndex.Properties) {
  val kind = "CoveringIndex"
}
object CoveringIndex {
  case class Properties(columns: Properties.Columns, schemaString: String, numBuckets: Int)
  object Properties {
    case class Columns(indexed: Seq[String], included: Seq[String])
  }
}

// IndexLogEntry-specific Signature that stores the signature provider and value.
case class Signature(provider: String, value: String)

// IndexLogEntry-specific LogicalPlanFingerprint to store fingerprint of logical plan.
case class LogicalPlanFingerprint(properties: LogicalPlanFingerprint.Properties) {
  val kind = "LogicalPlan"
}
object LogicalPlanFingerprint {
  case class Properties(signatures: Seq[Signature])
}

// IndexLogEntry-specific Hdfs that represents the source data.
case class Hdfs(properties: Hdfs.Properties) {
  val kind = "HDFS"
}
object Hdfs {
  case class Properties(content: Content)
}

// IndexLogEntry-specific Relation that represents the source relation.
case class Relation(
    rootPaths: Seq[String],
    data: Hdfs,
    dataSchemaJson: String,
    fileFormat: String,
    options: Map[String, String])

// IndexLogEntry-specific SparkPlan that represents the source plan.
case class SparkPlan(properties: SparkPlan.Properties) {
  val kind = "Spark"
}

object SparkPlan {
  case class Properties(
      relations: Seq[Relation],
      rawPlan: String, // null for now
      sql: String, // null for now
      fingerprint: LogicalPlanFingerprint)
}

// IndexLogEntry-specific Source that uses SparkPlan as a plan.
case class Source(plan: SparkPlan)

// IndexLogEntry that captures index-related information.
case class IndexLogEntry(
    name: String,
    derivedDataset: CoveringIndex,
    content: Content,
    source: Source,
    extra: Map[String, String])
    extends LogEntry(IndexLogEntry.VERSION) {

  def schema: StructType =
    DataType.fromJson(derivedDataset.properties.schemaString).asInstanceOf[StructType]

  def created: Boolean = state.equals(Constants.States.ACTIVE)

  def relations: Seq[Relation] = source.plan.properties.relations

  override def equals(o: Any): Boolean = o match {
    case that: IndexLogEntry =>
      config.equals(that.config) &&
        signature.equals(that.signature) &&
        numBuckets.equals(that.numBuckets) &&
        content.root.equals(that.content.root) &&
        source.equals(that.source) &&
        state.equals(that.state)
    case _ => false
  }

  def numBuckets: Int = derivedDataset.properties.numBuckets

  def config: IndexConfig = IndexConfig(name, indexedColumns, includedColumns)

  def indexedColumns: Seq[String] = derivedDataset.properties.columns.indexed

  def includedColumns: Seq[String] = derivedDataset.properties.columns.included

  def signature: Signature = {
    val sourcePlanSignatures = source.plan.properties.fingerprint.properties.signatures
    assert(sourcePlanSignatures.length == 1)
    sourcePlanSignatures.head
  }

  override def hashCode(): Int = {
    config.hashCode + signature.hashCode + numBuckets.hashCode + content.hashCode
  }
}

object IndexLogEntry {
  val VERSION: String = "0.1"

  def schemaString(schema: StructType): String = schema.json
}
