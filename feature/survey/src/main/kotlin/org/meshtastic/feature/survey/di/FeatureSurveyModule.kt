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
package org.meshtastic.feature.survey.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.meshtastic.feature.survey.SurveyDao
import org.meshtastic.feature.survey.SurveyDatabase

@Module
@ComponentScan("org.meshtastic.feature.survey")
class FeatureSurveyModule {
    @Single
    fun provideSurveyDatabase(context: Application): SurveyDatabase =
        Room.databaseBuilder(context, SurveyDatabase::class.java, "signal_survey.db")
            .setDriver(BundledSQLiteDriver())
            .build()

    @Single fun provideSurveyDao(database: SurveyDatabase): SurveyDao = database.surveyDao()
}
