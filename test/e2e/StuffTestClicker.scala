/**
 * Copyright (c) 2013 Kaj Magnus Lindberg (born 1979)
 */

package test.e2e

import org.openqa.selenium.Keys
import org.openqa.selenium.interactions.Actions
import com.debiki.v0.PageRole
import play.api.test.Helpers.testServerPort
import com.debiki.v0.Prelude._


/**
 * Does stuff, e.g. logs in or edits a comment, by clicking on stuff
 * and sometimes typing stuff. All this stuff is done inside ScalaTest tests.
 */
trait StuffTestClicker {
  self: DebikiBrowserSpec =>

  private var nextWebsiteId = 0
  private var firstGmailLogin = true
  private var knownWindowHandles = Set[String]()


  val DefaultHomepageTitle = "Default Homepage"


  def createWebsiteChooseNamePage = new Page {
    val url = s"$firstSiteOrigin/-/new-website/choose-name"
  }


  def nextSiteName(): String = {
    nextWebsiteId += 1
    s"test-site-$nextWebsiteId"
  }


  def clickCreateSite(
        siteName: String = null, alreadyOnNewWebsitePage: Boolean = false): String = {
    val name =
      if (siteName ne null) siteName
      else nextSiteName()

    s"create site $name" in {
      if (!alreadyOnNewWebsitePage)
        go to createWebsiteChooseNamePage

      click on "website-name"
      enter(name)
      click on "accepts-terms"
      click on cssSelector("input[type=submit]")

      clickLoginWithGmailOpenId()
    }

    name
  }


  def clickWelcomeLoginToDashboard(newSiteName: String) {
    "click login link on welcome owner page" in {
      // We should now be on page /-/new-website/welcome-owner.
      // There should be only one link, which takes you to /-/admin/.
      assert(pageSource contains "Website created")
      click on cssSelector("a")
    }

    "login to admin dashboard" in {
      clickLoginWithGmailOpenId()
      assert(pageSource contains "Welcome to your new website")
      webDriver.getCurrentUrl() must be === originOf(newSiteName) + "/-/admin/"
    }
  }


  def clickLoginWithGmailOpenId(approvePermissions: Boolean = true) {
    click on cssSelector("a.login-link-google")

    if (firstGmailLogin) {
      firstGmailLogin = false
      // 1. I've created a dedicated Gmail OpenID test account, see below.
      // 2. `enter(email)` throws: """Currently selected element is neither
      // a text field nor a text area""", in org.scalatest.selenium.WebBrowser,
      // so use `pressKeys` instead.
      click on "Email"
      pressKeys("debiki.tester@gmail.com")
      click on "Passwd"
      pressKeys("ZKFIREK90krI38bk3WK1r0")
      click on "signIn"
    }

    // Now Google should show another page, which ask about permissions.
    // Uncheck a certain remember choices checkbox, or this page won't be shown
    // next time (and then we cannot choose to deny access).
    click on "remember_choices_checkbox"

    if (approvePermissions)
      click on "approve_button"
    else
      click on "reject_button"
  }


  /**
   * Clicks e.g. Create... | New Blog, in the admin dashbar.
   * Switches to the new browser tab, with the new page.
   * Returns the id of the newly created (but not yet saved) page.
   */
  def clickCreateNewPage(pageRole: PageRole, suffix: String = null): String = {
    val (newPageLink, newPageTitlePart) = (pageRole, suffix) match {
      case (PageRole.Generic, _) => ("create-info-page", "New Page")
      case (PageRole.Blog, _) => ("create-blog", "Example Blog Post")
      case (PageRole.Forum, _) => ("create-forum", "New Forum")
      case (PageRole.Code, "css") => ("create-local-theme-style", "/themes/local/theme.css")
      case _ => fail(s"Bad page role: $pageRole")
    }

    click on cssSelector("#create-page-dropdown > .btn")
    click on newPageLink
    switchToNewlyOpenedWindow()
    eventually {
      pageSource.contains(newPageTitlePart) must be === true
    }
    waitForDashbar()

    findPageId()
  }


  /**
   * Returns the id of the new page.
   */
  // COULD place in a Blog page object? And return a BlogPost page object?
  def clickCreateBlogPost(): String = {
    // Sometimes the dashbar has not yet been loaded.
    eventually {
      click on cssSelector("a.create-blog-post")
    }
    waitForDashbar()
    // It takes a while for the new page to load.
    eventually {
      findPageId()
    }
  }


  def clickCreateForumTopic() {
    click on cssSelector(".dw-a-new-forum-topic")
    waitForDashbar()
  }


  def findPageId(): String = {
    val anyPageElem = eventually {
      find(cssSelector(".dw-page"))
    }
    val anyPageIdAttr = anyPageElem.flatMap(_.attribute("id"))
    val pageId = anyPageIdAttr match {
      case Some(id) => id.drop(5) // drop "page-"
      case None => fail("No .dw-page with an id attribute found")
    }
    pageId
  }


  /**
   * Clicks on the specified post, selects Improve in the inline menu,
   * edits the post and verifies that the changes were probably saved.
   *
   * If the dashbar might appear later on, be sure to call waitForDashbar()
   * before this function, because otherwise this function might accidentally
   * click the wrong links, e.g. click "View page settings" in the dashbar.
   * (All functions in this file currently calls waitForDashbar() when needed,
   * I think.)
   */
  def clickAndEdit(postId: String, newText: String) {
    info("click #post-$postId, select Improve")

    var xOffset = 6
    var yOffset = 6

    eventually {
      // Find the elem to edit. And find it again, and again..., read on:
      // 1. The very first time we get to here, we need to wait until the elem
      // becomes visible (in case the page is loading).
      // 2. After we've found the elem once, we sometimes need to find it again!:
      // For unknown reasons, the WebDriver ID of `textElem` sometimes becomes
      // stale, just after the elem has been found. Chrome then complains that
      // "Element does not exist in cache", and throws a
      //    org.openqa.selenium.StaleElementReferenceException  when we perform()
      // the below moveToElement() command (which is relative `textElem`).
      // So find `textElem` again here, to refresh the id.
      // See:  http://seleniumhq.org/exceptions/stale_element_reference.html
      var textElem =
        find(cssSelector(s"#post-$postId .dw-p-bd-blk > *")) getOrElse fail()

      // Click text and select Improve.
      // There might be whitespace in `textElem`, but we need to click text for
      // the inline menu with the Improve button to appear. So click here and
      // there along a diagonal line, starting in the upper left corner. (Clicking
      // in the middle won't always work.)
      val textElemSize = textElem.underlying.getSize
      yOffset = if (textElemSize.height < yOffset) 3 else yOffset + yOffset / 3
      xOffset = if (textElemSize.width < xOffset) 3 else xOffset + xOffset / 3
      (new Actions(webDriver)).moveToElement(
        textElem.underlying, xOffset, yOffset).click().perform()

      // Sometimes some moveByOffset click apparently happens to open the editor,
      // so don't try to open it, if it's open already.
      if (find(cssSelector(".CodeMirror textarea")).isEmpty) {
        def findImproveBtn = find(cssSelector(".dw-a-edit-i"))
        val improveBtn = findImproveBtn getOrElse fail()
        click on improveBtn
        // Very infrequently, the first click on the Improve button does not
        // trigger any real click, but only selects it. So click the Improve
        // button again and again until it's gone.
        while (findImproveBtn != None)
          click on improveBtn
      }

      // Unless the editor appears within a few seconds, try again to click
      // text and select Improve. (Sometimes the above click on Improve has
      // no effect. Perhaps this could happen if the dashbar happens to be loaded
      // just after `moveToElement` (above) but before `click`? The dashbar pushes
      // elems downwards a bit, so we might click the wrong thing?)
      import org.scalatest.time.{Span, Seconds}
      eventually(Timeout(Span(3, Seconds))) {
        find(cssSelector(".CodeMirror textarea")) getOrElse fail()
      }
    }

    val prettyNewText = {
      val firstLine = newText.takeWhile(0 <= _ - ' ')
      if (firstLine.length <= 50) firstLine
      else firstLine.take(47) + "..."
    }

    info(s"edit text to: ``$prettyNewText''")

    // Wait for network request that loads editor data.
    // Then focus editor and send keys.
    // ((It doesn't seem possible to click on CodeMirror. But using `sendKeys`
    // directly works. Alternatively, executing this JS string:
    //   driver.asInstanceOf[JavascriptExecutor].executeScript(
    //      """window.editor.setValue("Hello");""")
    // is also supposed to work, see e.g.:
    //   https://groups.google.com/forum/?fromgroups=#!topic/webdriver/Rhm-NZRBgXY ))
    eventually {
      find(cssSelector(".CodeMirror textarea")).map(_.underlying) match {
        case None =>
          // Try again later; editor loaded via Ajax request.
          fail()
        case Some(textarea) =>
          // Select all current text.
          textarea.sendKeys(Keys.chord(Keys.SHIFT, Keys.CONTROL, Keys.END))
          // Overwrite selected text.
          textarea.sendKeys(newText)
      }
    }

    info("click preview, then submit")

    // The edit tab id ends with a serial number, which depends on how
    // many edit forms have already been opened. So match only on the
    // start of the edit tab id.
    click on cssSelector(s"#post-$postId a[href^='#dw-e-tab-prvw_sno-']")
    click on cssSelector(s"#post-$postId .dw-f-e .dw-fi-submit")

    info("find new text in page source")

    eventually {
      find(cssSelector(s"#post-$postId .dw-p-bd")).map(_.text) must be ===
        Some(stripStartEndBlanks(newText))
    }
  }


  def openAndSwitchToFirstPage(pageTitle: String): WindowTarget = {
    // The Admin SPA does a network request to get a page listing.
    eventually {
      // Clicking the link opens a new browser tab.
      click on partialLinkText(pageTitle)
    }
    val window = switchToNewlyOpenedWindow()
    waitForDashbar()
    window
  }


  /**
   * Switches to some (any) newly opened browser window/tab, or fails the
   * current test. Returns a handle to that new window/tab.
   */
  def switchToNewlyOpenedWindow(): WindowTarget = {
    knownWindowHandles += webDriver.getWindowHandle()
    import collection.JavaConversions._
    val newHandle = webDriver.getWindowHandles.toList find {
      handle => !knownWindowHandles.contains(handle)
    } getOrElse {
      fail(s"No new handle to switch to; all handles: $knownWindowHandles")
    }
    webDriver.switchTo().window(newHandle)
    knownWindowHandles += newHandle
    window(newHandle)
  }


  def clickReturnToParentForum() {
    val hadDashbar = isDashbarVisible
    click on cssSelector(".parent-forums-list > li:last-child a")
    if (hadDashbar)
      waitForDashbar()
  }


  def clickReturnToBlogMainPage() {
    // Sometimes the dashbar might not yet have been loaded; wait for it to appear.
    val returnLink = eventually {
      find(cssSelector(".return-to-blog")) getOrElse fail()
    }
    click on returnLink
    waitForDashbar()
    // Wait for the write-new-blog-post button to appear, which indicates that
    // we're back on the blog main page.
    eventually {
      find(cssSelector(".create-blog-post")) must not be None
    }
  }

  def originOf(newSiteName: String) =
    s"http://$newSiteName.localhost:$testServerPort"


  /**
   * Waits until the dashbar has been loaded. It's better to wait until it's been
   * loaded, because when it appears it pushes other elems on the page downwards,
   * and this sometimes makes `moveToElement(..., x, y).click()` fail (if the
   * dashbar appears just between the mouse movement and the mouse click).
   */
  private def waitForDashbar() {
    eventually {
      isDashbarVisible must be === true
    }
  }


  private def isDashbarVisible: Boolean = {
    find(cssSelector(".debiki-dashbar-logo")) != None
  }

}

