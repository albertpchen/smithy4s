/*
 *  Copyright 2021-2022 Disney Streaming
 *
 *  Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package smithy4s.codegen

import sbt.Keys._
import sbt.util.CacheImplicits._
import sbt.{fileJsonFormatter => _, _}
import JsonConverters._

object Smithy4sCodegenPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  object autoImport {
    val smithy4sCodegen =
      taskKey[Seq[File]](
        "Generate .scala and other files from smithy specs (.smithy or .json files)"
      )

    val smithy4sVersion =
      settingKey[String]("Smithy4sVersion")

    val smithy4sInputDir =
      settingKey[File](
        "Input directory for smithy specs (.smithy or .json files)"
      )

    val smithy4sOutputDir =
      settingKey[File](
        "Output directory for .scala files generated by smithy4s"
      )

    val smithy4sResourceDir =
      settingKey[File](
        "Output directory for non-Scala files generated by smithy4s"
      )

    val smithy4sAllowedNamespaces =
      settingKey[List[String]](
        "Allow-list of namespaces that should be processed by the generator. If unset, considers all namespaces but stdlib ones"
      )

    val smithy4sExcludedNamespaces =
      settingKey[List[String]](
        "Disallow-list of namespaces that should not be processed by the generator. When set, namespaces are evicted as the last filtering step"
      )

    val smithy4sSmithyLibrary =
      settingKey[Boolean](
        "Sets whether this project should be used as a Smithy library by packaging the Smithy specs in the resulting jar"
      )

    val smithy4sLocalJars =
      taskKey[Seq[File]](
        List(
          "List of jars for local dependencies that should be used as sources of Smithy specs.",
          "Namespaces that were used for code generation in the upstream dependencies will be excluded from code generation in this project.",
          "By default, this includes the jars produced by packaging your project's build dependencies, so they'll need to be compiled for the codegen task to run.",
          "You can clear this (set to an empty list) if your Smithy specs don't have dependencies on other module."
        ).mkString(" ")
      )

    val Smithy4s =
      config("smithy4s").describedAs(
        "Dependencies containing Smithy code, used at codegen-time only."
      )

    // A shortcut to `"smithy4s,compile"` to use when defining dependencies that need to be consumed
    // at odegen-time (for their smithy specs) AND at compile-time (for the Scala-code they contain)
    val Smithy4sCompile = List(Smithy4s, Compile).map(_.name).mkString(",")

    val smithy4sModelTransformers =
      settingKey[List[String]](
        "List of transformer names that should be applied to the model prior to codegen"
      )
  }

  import autoImport._

  override lazy val buildSettings = Seq(
    smithy4sVersion := BuildInfo.version
  )

  override def projectConfigurations: Seq[Configuration] = Seq(Smithy4s)

  // Use this with any configuration to enable the codegen in it.
  def defaultSettings(config: Configuration) = Seq(
    config / smithy4sInputDir := (config / sourceDirectory).value / "smithy",
    config / smithy4sOutputDir := (config / sourceManaged).value,
    config / smithy4sResourceDir := (config / resourceManaged).value,
    config / smithy4sCodegen := cachedSmithyCodegen(config).value,
    config / smithy4sSmithyLibrary := true,
    config / smithy4sLocalJars := (config / internalDependencyAsJars).value
      .map(_.data),
    config / sourceGenerators += (config / smithy4sCodegen).map(
      _.filter(_.ext == "scala")
    ),
    config / resourceGenerators += (config / smithy4sCodegen).map(
      _.filter(_.ext != "scala")
    ),
    cleanFiles += (config / smithy4sOutputDir).value,
    config / smithy4sModelTransformers := List.empty
  )

  override lazy val projectSettings =
    defaultSettings(Compile)

  private def findCodeGenDependencies(
      updateReport: UpdateReport
  ): List[os.Path] =
    for {
      markerConfig <- List(Smithy4s)
      smithy4sConfigReport <- updateReport.configuration(markerConfig).toList
      module <- smithy4sConfigReport.modules
      artifactFile <- module.artifacts
    } yield {
      val (_, file) = artifactFile
      os.Path(file)
    }

  def cachedSmithyCodegen(conf: Configuration) = Def.task {
    val inputFiles =
      Option((conf / smithy4sInputDir).value)
        .filter(_.exists())
        .toList
    val outputPath = (conf / smithy4sOutputDir).value
    val resourceOutputPath = (conf / smithy4sResourceDir).value
    val allowedNamespaces =
      (conf / smithy4sAllowedNamespaces).?.value.map(_.toSet)
    val excludedNamespaces =
      (conf / smithy4sExcludedNamespaces).?.value.map(_.toSet)
    val updateReport = (conf / update).value

    val localDependencyJars =
      (conf / smithy4sLocalJars).value.map(os.Path(_)).toList

    val externalDependencyJars = findCodeGenDependencies(updateReport)
    val localJars = localDependencyJars ++ externalDependencyJars
    val res =
      (conf / resolvers).value.toList.collect { case m: MavenRepository =>
        m.root
      }
    val transforms = (conf / smithy4sModelTransformers).value
    val s = streams.value
    val skipResources: Set[FileType] =
      if ((conf / smithy4sSmithyLibrary).value) Set.empty
      else Set(FileType.Resource)
    val skipSet = skipResources

    val filePaths = inputFiles.map(_.getAbsolutePath())
    val codegenArgs = CodegenArgs(
      filePaths.map(os.Path(_)).toList,
      output = os.Path(outputPath),
      resourceOutput = os.Path(resourceOutputPath),
      skip = skipSet,
      discoverModels = true, // we need protocol here
      allowedNS = allowedNamespaces,
      excludedNS = excludedNamespaces,
      repositories = res,
      dependencies = List.empty,
      transformers = transforms,
      localJars = localJars
    )

    val cached =
      Tracked.inputChanged[CodegenArgs, Seq[File]](
        s.cacheStoreFactory.make("input")
      ) {
        Function.untupled {
          Tracked.lastOutput[(Boolean, CodegenArgs), Seq[File]](
            s.cacheStoreFactory.make("output")
          ) { case ((inputChanged, args), outputs) =>
            if (inputChanged || outputs.isEmpty) {
              val resPaths = smithy4s.codegen.Codegen
                .processSpecs(args)
                .toList
              resPaths.map(path => new File(path.toString))
            } else {
              outputs.getOrElse(Seq.empty)
            }
          }
        }
      }

    cached(codegenArgs)
  }
}
