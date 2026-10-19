/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.meshtastic.android.library)
    alias(libs.plugins.meshtastic.android.library.compose)
    alias(libs.plugins.meshtastic.koin)
    alias(libs.plugins.meshtastic.android.room)
    alias(libs.plugins.meshtastic.kotlinx.serialization)
}

android {
    namespace = "org.meshtastic.feature.survey"
    resourcePrefix = "survey_"

    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.navigation)
    implementation(projects.core.repository)
    implementation(projects.core.resources)
    implementation(projects.core.ui)

    implementation(libs.compose.multiplatform.foundation)
    implementation(libs.compose.multiplatform.material3)
    implementation(libs.compose.multiplatform.ui)
    implementation(libs.compose.multiplatform.resources)
    implementation(libs.jetbrains.navigation3.ui)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.meshtastic.protobufs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.koin.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)

    testImplementation(projects.core.testing)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.androidx.sqlite.bundled.jvm)
    // kotlin-test-junit is JUnit 4; the convention turns on the JUnit Platform, which runs it through vintage.
    testRuntimeOnly(libs.junit.vintage.engine)
}
