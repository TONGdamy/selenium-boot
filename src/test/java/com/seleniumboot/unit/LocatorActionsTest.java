package com.seleniumboot.unit;

import com.seleniumboot.config.SeleniumBootConfig;
import com.seleniumboot.driver.DriverManager;
import com.seleniumboot.internal.SeleniumBootContext;
import com.seleniumboot.locator.Locator;
import com.seleniumboot.locator.LocatorException;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Public keyboard, focus and pointer actions share the existing terminal-action contract. */
public class LocatorActionsTest {

    private static final By TARGET = By.id("target");
    private static final String FOCUS_SCRIPT = "arguments[0].focus();";

    private WebDriver driver;
    private JavascriptExecutor js;
    private Interactive interactive;
    private MockedStatic<DriverManager> driverManagerMock;
    private MockedStatic<SeleniumBootContext> contextMock;

    @BeforeMethod
    public void setup() {
        driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class, Interactive.class));
        js = (JavascriptExecutor) driver;
        interactive = (Interactive) driver;
        driverManagerMock = mockStatic(DriverManager.class);
        driverManagerMock.when(DriverManager::getDriver).thenReturn(driver);

        SeleniumBootConfig config = new SeleniumBootConfig();
        SeleniumBootConfig.Timeouts timeouts = new SeleniumBootConfig.Timeouts();
        timeouts.setExplicit(1);
        config.setTimeouts(timeouts);
        contextMock = mockStatic(SeleniumBootContext.class);
        contextMock.when(SeleniumBootContext::getConfig).thenReturn(config);
    }

    @AfterMethod
    public void teardown() {
        driverManagerMock.close();
        contextMock.close();
    }

    @DataProvider(name = "actions")
    public Object[][] actions() {
        return new Object[][] {{"press"}, {"focus"}, {"doubleClick"}, {"rightClick"}};
    }

    @Test(dataProvider = "actions")
    public void action_readyElement_performsRequestedActionOnce(String action) {
        WebElement element = readyElement();
        when(driver.findElements(TARGET)).thenReturn(List.of(element));

        act(action);

        verifySuccessfulAction(action, element, 1);
        verify(driver, times(1)).findElements(TARGET);
        verify(element, never()).clear();
        verify(element, never()).click();
    }

    @Test(dataProvider = "actions")
    public void action_elementAppearsLater_reResolvesBeforeActing(String action) {
        WebElement element = readyElement();
        when(driver.findElements(TARGET)).thenReturn(List.of()).thenReturn(List.of(element));

        act(action);

        verifySuccessfulAction(action, element, 1);
        verify(driver, times(2)).findElements(TARGET);
    }

    @Test(dataProvider = "actions")
    public void action_elementInitiallyHidden_waitsForVisibility(String action) {
        WebElement element = readyElement();
        when(element.isDisplayed()).thenReturn(false).thenReturn(true);
        when(driver.findElements(TARGET)).thenReturn(List.of(element));

        act(action);

        verifySuccessfulAction(action, element, 1);
        verify(driver, times(2)).findElements(TARGET);
    }

    @Test(dataProvider = "actions")
    public void action_elementStaleDuringReadiness_reResolvesFreshElement(String action) {
        WebElement stale = readyElement();
        when(stale.isDisplayed()).thenThrow(new StaleElementReferenceException("re-rendered"));
        WebElement fresh = readyElement();
        when(driver.findElements(TARGET)).thenReturn(List.of(stale)).thenReturn(List.of(fresh));

        act(action);

        verifySuccessfulAction(action, fresh, 1);
        verify(driver, times(2)).findElements(TARGET);
    }

    @Test(dataProvider = "actions")
    public void action_elementStaleDuringAction_retriesActionOnFreshElement(String action) {
        WebElement stale = readyElement();
        WebElement fresh = readyElement();
        when(driver.findElements(TARGET)).thenReturn(List.of(stale)).thenReturn(List.of(fresh));
        failActionWithStale(action, stale, false);

        act(action);

        verifySuccessfulAction(action, fresh, isPointerAction(action) ? 2 : 1);
        verify(driver, times(2)).findElements(TARGET);
        if (isPointerAction(action)) {
            ArgumentCaptor<Collection<Sequence>> gestures = ArgumentCaptor.captor();
            verify(interactive, times(2)).perform(gestures.capture());
            assertSame(pointerMove(gestures.getAllValues().get(0)).get("origin"), stale);
        }
    }

    @Test(dataProvider = "actions")
    public void action_elementNeverAppears_throwsLocatorExceptionWithTimeoutCause(String action) {
        when(driver.findElements(TARGET)).thenReturn(List.of());

        LocatorException error = expectThrows(LocatorException.class, () -> act(action));

        assertTrue(error.getCause() instanceof TimeoutException);
        assertTrue(error.getMessage().contains("waited 1s"), error.getMessage());
        verify(driver, atLeast(2)).findElements(TARGET);
        verify(js, never()).executeScript(anyString(), any(WebElement.class));
        verify(interactive, never()).perform(any());
    }

    @Test(dataProvider = "actions")
    public void action_elementAlwaysStaleDuringAction_timesOutInsteadOfReportingSuccess(String action) {
        WebElement stale = readyElement();
        when(driver.findElements(TARGET)).thenReturn(List.of(stale));
        failActionWithStale(action, stale, true);

        TimeoutException error = expectThrows(TimeoutException.class, () -> act(action));

        assertTrue(error.getCause() instanceof StaleElementReferenceException);
        verify(driver, atLeast(2)).findElements(TARGET);
    }

    @Test(dataProvider = "actions")
    public void action_elementNeverVisible_timesOutWithoutActing(String action) {
        WebElement hidden = readyElement();
        when(hidden.isDisplayed()).thenReturn(false);
        when(driver.findElements(TARGET)).thenReturn(List.of(hidden));

        expectThrows(TimeoutException.class, () -> act(action));

        verify(hidden, never()).sendKeys(any(Keys[].class));
        verify(js, never()).executeScript(anyString(), any(WebElement.class));
        verify(interactive, never()).perform(any());
        verify(driver, atLeast(2)).findElements(TARGET);
    }

    @Test
    public void doubleClick_elementInitiallyDisabled_waitsUntilEnabled() {
        WebElement element = readyElement();
        when(element.isEnabled()).thenReturn(false).thenReturn(true);
        when(driver.findElements(TARGET)).thenReturn(List.of(element));

        Locator.of(TARGET).doubleClick();

        verifySuccessfulAction("doubleClick", element, 1);
        verify(driver, times(2)).findElements(TARGET);
    }

    private static WebElement readyElement() {
        WebElement element = mock(WebElement.class);
        when(element.isDisplayed()).thenReturn(true);
        when(element.isEnabled()).thenReturn(true);
        return element;
    }

    private void act(String action) {
        Locator locator = Locator.of(TARGET);
        switch (action) {
            case "press" -> locator.press(Keys.ENTER, Keys.ESCAPE);
            case "focus" -> locator.focus();
            case "doubleClick" -> locator.doubleClick();
            case "rightClick" -> locator.rightClick();
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static boolean isPointerAction(String action) {
        return action.equals("doubleClick") || action.equals("rightClick");
    }

    private void failActionWithStale(String action, WebElement element, boolean always) {
        StaleElementReferenceException error = new StaleElementReferenceException("re-rendered during action");
        switch (action) {
            case "press" -> doThrow(error).when(element).sendKeys(Keys.ENTER, Keys.ESCAPE);
            case "focus" -> when(js.executeScript(FOCUS_SCRIPT, element)).thenThrow(error);
            case "doubleClick", "rightClick" -> {
                if (always) {
                    doThrow(error).when(interactive).perform(any());
                } else {
                    doThrow(error).doNothing().when(interactive).perform(any());
                }
            }
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void verifySuccessfulAction(String action, WebElement element, int gestureAttempts) {
        switch (action) {
            case "press" -> verify(element, times(1)).sendKeys(Keys.ENTER, Keys.ESCAPE);
            case "focus" -> verify(js, times(1)).executeScript(FOCUS_SCRIPT, element);
            case "doubleClick", "rightClick" -> {
                ArgumentCaptor<Collection<Sequence>> gestures = ArgumentCaptor.captor();
                verify(interactive, times(gestureAttempts)).perform(gestures.capture());
                Collection<Sequence> last = gestures.getValue();
                assertSame(pointerMove(last).get("origin"), element);
                List<?> events = (List<?>) last.iterator().next().encode().get("actions");
                int expectedClicks = action.equals("doubleClick") ? 2 : 1;
                int expectedButton = action.equals("doubleClick") ? 0 : 2;
                assertEquals(events.stream().filter(e -> "pointerDown".equals(((Map<?, ?>) e).get("type"))).count(),
                        (long) expectedClicks);
                assertEquals(events.stream().filter(e -> "pointerUp".equals(((Map<?, ?>) e).get("type"))).count(),
                        (long) expectedClicks);
                events.stream().map(e -> (Map<?, ?>) e)
                        .filter(e -> "pointerDown".equals(e.get("type")) || "pointerUp".equals(e.get("type")))
                        .forEach(e -> assertEquals(e.get("button"), expectedButton));
            }
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static Map<?, ?> pointerMove(Collection<Sequence> gesture) {
        List<?> events = (List<?>) gesture.iterator().next().encode().get("actions");
        return (Map<?, ?>) events.get(0);
    }
}
