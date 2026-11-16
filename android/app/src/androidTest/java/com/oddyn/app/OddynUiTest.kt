package com.oddyn.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OddynUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun findsMatchesOpensDetailsPersistsAnExpressAndReportsNetworkFailure() {
        ui.onNodeWithText("Демо").assertIsDisplayed()
        ui.onNodeWithText("Найти ставки").performClick()
        await(hasText("Северный берег · Полярная звезда"))
        ui.onNodeWithText("Северный берег · Полярная звезда").performClick()
        await(hasText("Анализ матча"))
        scrollTo("Сохранить прогноз")
        ui.onAllNodesWithText("Сохранить прогноз")[0].performClick()
        await(hasText("Прогноз сохранён на устройстве."))
        scrollTo("В экспресс")
        ui.onAllNodesWithText("В экспресс")[0].performClick()
        await(hasText("Плечо добавлено в экспресс.", substring = false), waitMillis = 15000)

        ui.onNodeWithContentDescription("Назад").performClick()
        scrollTo("Айсберг · Тайфун")
        ui.onNodeWithText("Айсберг · Тайфун").performClick()
        await(hasText("Анализ матча"))
        scrollTo("В экспресс")
        ui.onAllNodesWithText("В экспресс")[0].performClick()

        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Экспрессы").performClick()
        scrollTo("Сохранить подборку")
        ui.onNodeWithText("Сохранить подборку").performClick()
        scrollTo("Теоретический коэффициент 3,168")
        ui.onNodeWithText("Теоретический коэффициент 3,168").assertIsDisplayed()

        ui.activityRule.scenario.recreate()
        scrollTo("Теоретический коэффициент 3,168")
        ui.onNodeWithText("Теоретический коэффициент 3,168").assertIsDisplayed()

        ui.onNodeWithText("История").performClick()
        scrollTo("Меньше 3,5", substring = true)
        ui.onNodeWithText("Тотал голов · Меньше 3,5").assertIsDisplayed()

        ui.onNodeWithText("Настройки").performClick()
        ui.onNodeWithText("Реальные данные").performClick()
        scrollTo("Адрес сервера")
        ui.onNodeWithText("Адрес сервера").performTextInput("http://127.0.0.1:1")
        scrollTo("Сохранить адрес")
        ui.onNodeWithText("Сохранить адрес").performClick()
        await(hasText("Адрес сервера сохранён."))
        ui.onNodeWithText("Главная").performClick()
        scrollTo("Найти ставки")
        ui.onNodeWithText("Найти ставки").performClick()
        await(hasText("Данные не обновлены"))
        scrollTo("Данные не обновлены")
        await(hasText("Сервер недоступен", substring = true))
        ui.onNodeWithText("Северный берег · Полярная звезда").assertDoesNotExist()
    }

    private fun await(matcher: SemanticsMatcher, waitMillis: Long = 10000) {
        ui.waitUntil(waitMillis) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String, substring: Boolean = false) {
        ui.onNodeWithTag("page-list").performScrollToNode(hasText(text, substring = substring))
    }
}
