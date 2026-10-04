/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the dependency/license list for the Incubator IP-clearance / release-checklist
 * item "all items depended upon by the project are covered by approved licenses", as an
 * HTML `<td>` fragment ready to paste into the checklist.
 *
 * It is derived from the same inputs as the binary LICENSE ([GenerateBinaryLicense]): the
 * shipped dependency coordinates and the CycloneDX SBOM, so the two cannot drift. Each
 * dependency is listed with the license(s) the SBOM reports, exactly as reported.
 *
 * Like the LICENSE appendix, this is a disclosure, not a license policy: it carries no
 * allow-list and makes no Category A/B judgement. Whether the listed licenses are
 * acceptable is a human review before the checklist item is signed off.
 * A dependency missing from the SBOM fails the task, as in [GenerateBinaryLicense].
 */
abstract class GenerateIpClearanceLicenseReport : DefaultTask() {

    /** The generated CycloneDX SBOM (`application.cdx.json`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sbom: RegularFileProperty

    /** The dependencies that actually ship, as `"group:name:version"` strings. */
    @get:Input
    abstract val bundledCoordinates: ListProperty<String>

    /** Where the HTML fragment is written. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val sbomLicenses = SbomLicenses(sbom.get().asFile)

        val notInSbom = mutableListOf<String>()
        val items = mutableListOf<Pair<String, List<License>>>()
        for (coordinate in bundledCoordinates.get()) {
            val licenses = sbomLicenses.lookup(coordinate)
            if (licenses.isEmpty()) notInSbom += coordinate else items += coordinate.substringBeforeLast(':') to licenses
        }
        if (notInSbom.isNotEmpty()) {
            throw GradleException(
                "Bundled dependencies absent from the CycloneDX SBOM:\n" +
                    notInSbom.joinToString("\n") { "  - $it" } +
                    "\nEnsure cyclonedxBom covers the runtime classpath.",
            )
        }

        val html = StringBuilder()
        html.append("<td>Check and make sure that all items depended upon by the project are\n")
        html.append("    covered by one or more of the following approved licenses: Apache, BSD,\n")
        html.append("    Artistic, MIT/X, MIT/W3C, MPL 1.1, or something with essentially the same\n")
        html.append("    terms. &mdash; All runtime dependencies bundled in the release (derived from\n")
        html.append("    the CycloneDX SBOM):\n")
        html.append("  <ul>\n")
        for ((groupArtifact, licenses) in items.distinctBy { it.first }.sortedBy { it.first }) {
            html.append("    <li>").append(escape(groupArtifact)).append(" &mdash; ")
                .append(escape(licenses.joinToString(" / ") { it.label })).append("</li>\n")
        }
        html.append("  </ul>\n")
        html.append("</td>\n")

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(html.toString())
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
