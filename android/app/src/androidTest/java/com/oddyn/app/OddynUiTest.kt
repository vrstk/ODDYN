package com.oddyn.app

import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
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
        addFirstMarketToExpress()
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Экспрессы").performSemanticsAction(SemanticsActions.OnClick)
        await(hasText("1 из 10 плеч"))
        ui.onNodeWithText("Главная").performSemanticsAction(SemanticsActions.OnClick)
        scrollTo("Айсберг · Тайфун")
        ui.onNodeWithText("Айсберг · Тайфун").performSemanticsAction(SemanticsActions.OnClick)
        await(hasText("Анализ матча"))
        addFirstMarketToExpress()

        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Экспрессы").performSemanticsAction(SemanticsActions.OnClick)
        await(hasText("2 из 10 плеч"))
        scrollTo("Сохранить подборку")
        ui.onNodeWithText("Сохранить подборку").performSemanticsAction(SemanticsActions.OnClick)
        awaitScrollable("Теоретический коэффициент 3,168")
        ui.onNodeWithText("Теоретический коэффициент 3,168").assertIsDisplayed()

        ui.activityRule.scenario.recreate()
        awaitScrollable("Теоретический коэффициент 3,168")
        ui.onNodeWithText("Теоретический коэффициент 3,168").assertIsDisplayed()

        ui.onNodeWithText("История").performSemanticsAction(SemanticsActions.OnClick)
        awaitScrollable("Тотал голов · Меньше 3,5")
        ui.onNodeWithText("Тотал голов · Меньше 3,5").assertIsDisplayed()

        ui.onNodeWithText("Настройки").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("Реальные данные").performSemanticsAction(SemanticsActions.OnClick)
        scrollTo("Адрес сервера")
        ui.onNodeWithText("Адрес сервера").performTextInput("http://127.0.0.1:1")
        scrollTo("Сохранить адрес")
        ui.onNodeWithText("Сохранить адрес").performSemanticsAction(SemanticsActions.OnClick)
        awaitScrollable("Соединение ещё не проверено")
        ui.onNodeWithText("Главная").performSemanticsAction(SemanticsActions.OnClick)
        scrollTo("Найти ставки")
        ui.onNodeWithText("Найти ставки").performClick()
        awaitScrollable("Данные не обновлены")
        await(hasText("Сервер недоступен", substring = true))
        ui.onNodeWithText("Северный берег · Полярная звезда").assertDoesNotExist()
    }

    private fun addFirstMarketToExpress() {
        scrollTo("В экспресс")
        ui.onAllNodesWithText("В экспресс")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun await(matcher: SemanticsMatcher, waitMillis: Long = 20000) {
        ui.waitUntil(waitMillis) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitScrollable(text: String) {
        ui.waitUntil(20000) { runCatching { scrollTo(text) }.isSuccess }
    }

    private fun scrollTo(text: String) {
        ui.onNodeWithTag("page-list").performScrollToNode(hasText(text))
    }
}
