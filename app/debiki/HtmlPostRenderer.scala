/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package debiki

import com.debiki.v0._
//import com.twitter.ostrich.stats.Stats
import java.{util => ju, io => jio}
import scala.collection.JavaConversions._
import _root_.scala.xml.{NodeSeq, Node, Elem, Text, XML, Attribute}
import FlagReason.FlagReason
import Prelude._
import HtmlUtils._
import HtmlPostRenderer._



case class RenderedPost(
  headAndBodyHtml: Node,
  actionsHtml: Elem,
  topRatingsText: Option[String])


case class RenderedPostHeader(
  html: NodeSeq,
  topRatingsText: Option[String])


case class RenderedPostBody(
  html: NodeSeq,
  approxLineCount: Int,
  replyBtnText: NodeSeq)



case class HtmlPostRenderer(
  page: PageParts,
  pageStats: PageStats,
  hostAndPort: String,
  nofollowArticle: Boolean = true) {


  def renderPost(postId: String, uncollapse: Boolean = false): RenderedPost = {
    val post = page.getPost(postId) getOrElse
       assErr("DwE209X5", "post id "+ postId +" on page "+ page.id)

    if (post.isTreeDeleted) {
      renderDeletedTree(post)
    }
    else if (post.isTreeCollapsed && !uncollapse) {
      renderCollapsedTree(post)
    }
    else if (post.isDeleted) {
      renderDeletedComment(post)
    }
    else if (post.isPostCollapsed && !uncollapse) {
      renderCollapsedComment(post)
    }
    else if (post.id == PageParts.TitleId) {
      val titleHtml = renderPageTitle(post)
      RenderedPost(titleHtml, actionsHtml = <span></span>, topRatingsText = None)
    }
    else {
      renderPostImpl(post, nofollowArticle)
    }
  }


  private def renderPostImpl(post: Post, nofollowArticle: Boolean): RenderedPost = {
    val postHeader =
      if (post.id == PageParts.BodyId) {
        // Body author and date info rendered separately, for the page body.
        RenderedPostHeader(Nil, None)
      }
      else {
        renderPostHeader(post, Some(pageStats))
      }

    val postBody = renderPostBody(post, hostAndPort, nofollowArticle)

    val long = postBody.approxLineCount > 9
    val cutS = if (long) " dw-x-s" else ""

    val cssArtclPost = if (post.id != PageParts.BodyId) "" else " dw-ar-p"
    val commentHtml =
      <div id={htmlIdOf(post)} class={"dw-p" + cssArtclPost + cutS}>{
        postHeader.html ++
        postBody.html
      }</div>

    RenderedPost(commentHtml, actionsHtml = renderActionLinks(post),
      topRatingsText = postHeader.topRatingsText)
  }

}



object HtmlPostRenderer {


  def renderDeletedTree(post: Post): RenderedPost = {
    renderDeletedComment(post, wholeTree = true)
  }


  def renderDeletedComment(post: Post, wholeTree: Boolean = false): RenderedPost = {
    val page = post.debate
    val deletion = post.firstDelete.get
    val deleter = page.people.authorOf_!(deletion)
    // COULD add itemscope and itemtype attrs, http://schema.org/Comment
    val html =
      <div id={htmlIdOf(post)} class='dw-p dw-p-dl'>
        <div class='dw-p-hd'>{
          if (wholeTree) "Thread" else "1 comment"
          } deleted by { _linkTo(deleter.user_!)
          /* COULD show flagsTop, e.g. "flagged spam".
            COULD include details, shown on click:
            Posted on ...,, rated ... deleted on ..., reasons for deletion: ...
            X flags: ... -- but perhaps better / easier with a View link,
            that opens the deleted post, incl. details, in a new browser tab?  */}
        </div>
      </div>
    RenderedPost(html, actionsHtml = renderActionsForDeleted(post), topRatingsText = None)
  }


  def renderCollapsedTree(post: Post): RenderedPost = {
    // Include the post id, so Javascript finds the post and inits action links,
    // e.g. links that uncollapses the thread.
    RenderedPost(<div id={htmlIdOf(post)} class="dw-p"></div>,
      actionsHtml = renderActionsForCollapsed(post), None)
  }


  def renderCollapsedComment(post: Post): RenderedPost = {
    val html =
      <div id={htmlIdOf(post)} class="dw-p dw-zd">
        <a class="dw-z">Click to show this comment</a>
      </div>
    RenderedPost(html, actionsHtml = renderActionsForCollapsed(post), topRatingsText = None)
  }


  /**
   * Renders a .dw-p-hd tag reading:
   *  "By (author) (date), improved by (editor)
   *    Flagged (top flags) Rated (top ratings)"
   * If anyPageStats is None, skips "Flagged ... Rated ..." statistics/info.
   */
  def renderPostHeader(post: Post, anyPageStats: Option[PageStats])
        : RenderedPostHeader = {
    if (post.loginId == DummyPage.DummyAuthorLogin.id)
      return RenderedPostHeader(Nil, None)

    def page = post.debate
    val author = post.user_!

    val (flagsTop: NodeSeq, flagsDetails: NodeSeq) =
      if (anyPageStats.isDefined) renderFlags(post)
      else (Nil: NodeSeq, Nil: NodeSeq)

    val (topTagsAsText: Option[String],
        ratingTagsTop: NodeSeq,
        ratingTagsDetails: NodeSeq) =
      if (anyPageStats.isDefined) renderRatings(post, anyPageStats.get)
      else (None, Nil: NodeSeq, Nil: NodeSeq)

    val editInfo =
      // If closed: <span class='dw-p-re-cnt'>{count} replies</span>
      if (post.lastEditAppliedAt.isEmpty) Nil
      else {
        val editorsCount = post.numDistinctEditors
        lazy val editor = page.people.user_!(post.lastEditorId getOrDie "DwE94IR7")
        <span class='dw-p-hd-e'>{
            Text(", improved ") ++
            (if (editorsCount > 1) {
              Text("by ") ++ <a>various people</a>
            } else if (editor.id != author.id) {
              Text("by ") ++ _linkTo(editor)
            } else {
              // Edited by the author. Don't repeat his/her name.
              Nil
            })
          }{dateAbbr(post.textLastEditedAt, "dw-p-at")}
        </span>
      }

    val permalink =
      if (PageParts.isArticleOrConfigPostId(post.id)) scala.xml.Null
      else <a class="dw-p-link">#{post.id}</a>

    val cssArticlePostHeader =
      if (post.id == PageParts.BodyId) " dw-ar-p-hd"
      else ""

    val commentHtml =
      <div class={"dw-p-hd" + cssArticlePostHeader}>
        By { _linkTo(author)}{ dateAbbr(post.creationDati, "dw-p-at")
        }{ permalink }{ flagsTop }{ ratingTagsTop }{ editInfo }{ flagsDetails
        }{ ratingTagsDetails }
      </div>

    RenderedPostHeader(html = commentHtml, topRatingsText = topTagsAsText)
  }


  private def renderFlags(post: Post): (NodeSeq, NodeSeq) = {
    if (post.flags isEmpty)
      return (Nil: NodeSeq, Nil: NodeSeq)

    import HtmlForms.FlagForm.prettify
    val mtime = toIso8601T(post.lastFlag.get.ctime)
    val fbr = post.flagsByReasonSorted

    val topFlags =
      <span class='dw-p-flgs-top'>, flagged <em>{
        prettify(fbr.head._1).toLowerCase
      }</em></span>

    val allFlagListItems =
      for ((r: FlagReason, fs: List[Flag]) <- fbr) yield
        <li class="dw-flg">{
          // The `×' is the multiplication sign, "\u00D7".
          prettify(r).toLowerCase +" × "+ fs.length.toString
        } </li>

    val allFlags =
      <div class='dw-p-flgs-all' data-mtime={mtime}>{
        post.flags.length } flags: <ol class='dw-flgs'>{
          allFlagListItems
        }</ol>
      </div>

    (topFlags, allFlags)
  }


  private def renderRatings(post: Post, pageStats: PageStats) = {
    val postRatingStats: PostRatingStats = pageStats.ratingStatsFor(post.id)
    // Sort the rating tags by their observed fittingness, descending
    // (the most popular tags first).
    val tagStatsSorted = postRatingStats.tagStats.toList.sortBy(
        -_._2.fitness.observedMean)
    val topTags = if (tagStatsSorted isEmpty) Nil else {
      // If there're any really popular tags ([the lower confidence limit on
      // the probability that they're used] is > 0.4),
      // show all those. Otherwise, show only the most popular tag(s).
      // (Oops, need not be `max' -- they're sorted by the *measured* prob,
      // not the lower conf limit -- well, hardly matters.)
      val maxLowerConfLimit = tagStatsSorted.head._2.fitness.lowerLimit
      val minLower = math.min(0.4, maxLowerConfLimit)
      tagStatsSorted.takeWhile(_._2.fitness.lowerLimit >= minLower)
    }

    val topTagsAsText: Option[String] = {
      def showRating(tagAndStats: Pair[String, TagStats]): String = {
        val tagName = tagAndStats._1
        val tagFitness = tagAndStats._2.fitness
        // A rating tag like "important!!" means "really important", many
        // people agree. And "important?" means "perhaps somewhat important",
        // some people agree.
        // COULD reduce font-size of ? to 85%, it's too conspicuous.
        val mark =
          if (tagFitness.lowerLimit > 0.9) "!!"
          else if (tagFitness.lowerLimit > 0.7) "!"
          else if (tagFitness.lowerLimit > 0.3) ""
          else "?"
        tagName + mark
        // COULD reduce font size of mark to 85%, or it clutters the ratings.
      }
      if (topTags isEmpty) None
      else Some(topTags.take(3).map(showRating(_)).mkString(", "))
    }

    val (ratingTagsTop: NodeSeq, ratingTagsDetails: NodeSeq) = {
      val rats = tagStatsSorted
      if (rats.isEmpty) (Nil: NodeSeq, Nil: NodeSeq)
      else {
        // List popular rating tags. Then all tags and their usage percents,
        // but those details are shown only if one clicks the post header.
        val topTagsAsHtml =
          if (topTagsAsText isEmpty) Nil
          else <span class='dw-p-r dw-p-r-top'>, rated <em>{
            topTagsAsText.get}</em></span>

        val tagDetails = <div class='dw-p-r-all'
             data-mtime={toIso8601T(postRatingStats.lastRatingDate)}>{
          postRatingStats.ratingCountUntrusty} ratings:
          <ol class='dw-p-r dw-rs'>{
          // Don't change whitespace, or `editInfo' perhaps won't
          // be able to append a ',' with no whitespace in front.
          for ((tagName: String, tagStats: TagStats) <- rats) yield
          <li class="dw-r" data-stats={
              ("lo: %.0f" format (100 * tagStats.fitness.lowerLimit)) +"%, "+
              "sum: "+ tagStats.countWeighted}> {
            tagName +" %.0f" format (
               100 * tagStats.fitness.observedMean)}% </li>
        }</ol></div>

        (topTagsAsHtml, tagDetails)
      }
    }

    (topTagsAsText, ratingTagsTop, ratingTagsDetails)
  }


  def renderPageTitle(titlePost: Post): Node = {
    // The title is a post, itself.
    // Therefore this XML is almost identical to the XML
    // for the post that this title entitles.
    // In the future, I could make a recursive call to
    // _renderPost, to render the title. Then it would be
    // possible to reply-inline to the title.
    // (Don't wrap the <h1> in a <header>; there's no need to wrap single
    // tags in a <header>.)
      <div id={htmlIdOf(titlePost)} class='dw-p dw-p-ttl'>
        <div class='dw-p-bd'>
          <div class='dw-p-bd-blk'>
            <h1 class='dw-p-ttl'>{
              titlePost.approvedText getOrElse "(Page title not yet approved)"
            }</h1>
          </div>
        </div>
      </div>
  }


  def htmlIdOf(post: Post) = s"post-${post.id}"


  def _linkTo(user: User) = HtmlPageSerializer.linkTo(user)


  def renderPostBody(post: Post, hostAndPort: String, nofollowArticle: Boolean)
        : RenderedPostBody = {
    val cssArtclBody = if (post.id != PageParts.BodyId) "" else " dw-ar-p-bd"
    val isBodyOrArtclQstn = post.id == PageParts.BodyId // || post.meta.isArticleQuestion
    val (xmlTextInclTemplCmds, approxLineCount) =
      HtmlPageSerializer._markupTextOf(post, hostAndPort, nofollowArticle)

    // Find any customized reply button text.
    var replyBtnText: NodeSeq = xml.Text("Reply")
    if (isBodyOrArtclQstn) {
      HtmlPageSerializer.findChildrenOfNode(
        withClass = "debiki-0-reply-button-text",
        in = xmlTextInclTemplCmds) foreach { replyBtnText = _ }
    }

    val xmlText: NodeSeq = xmlTextInclTemplCmds // old rename
    //if (!isRootOrArtclQstn) (Nil, xmlTextInclTemplCmds)
    //else partitionChildsWithDataAttrs(in = xmlTextInclTemplCmds)

    val postBodyHtml =
      <div class={"dw-p-bd"+ cssArtclBody}>
        <div class='dw-p-bd-blk'>{ xmlText
          // (Don't  place a .dw-i-ts here. Splitting the -bd into
          // -bd-blks and -i-ts is better done client side, where the
          // heights of stuff is known.)
        }</div>
      </div>

    RenderedPostBody(html = postBodyHtml, approxLineCount = approxLineCount,
      replyBtnText = replyBtnText)
  }


  def renderActionLinks(post: Post): Elem = {

    var moreActionLinks: NodeSeq = Nil
    var suggestionsOld: NodeSeq = Nil
    var suggestionsNew: NodeSeq = Nil

    // ----- Reply and rate links

    val (replyLink, rateLink) = {
      if (post.isDeleted) (Nil, Nil)
      else (
        <a class="dw-a dw-a-reply">Reply</a>,
        <a class="dw-a dw-a-rate" title="Vote up or down">Like?</a>)
    }

    // ----- Flag links

    if (post.numFlags > 0) {
      val pendingClass = if (post.numPendingFlags == 0) "" else " dw-a-pending-review"
      val html = <a class={"dw-a dw-a-flag-suggs icon-flag" + pendingClass}
                    title="View flags, e.g. if flagged as spam">×{ post.numFlags }</a>
      if (post.numPendingFlags == 0) suggestionsOld ++= html
      else suggestionsNew ++= html
    }

    moreActionLinks ++= <a class="dw-a dw-a-flag icon-flag">Report</a>

    // ----- Edit suggestions

    if (post.numPendingEditSuggestions > 0)
      suggestionsNew ++= <a class="dw-a dw-a-edit icon-edit dw-a-pending-review"
           title="View edit suggestions">×{post.numPendingEditSuggestions}</a>

    // ----- Collapse links

    suggestionsNew ++= renderUncollapseSuggestions(post)

    if (post.numCollapseVotesPending > 0)
      suggestionsNew ++= <a class="dw-a dw-a-collapse-suggs icon-collapse dw-a-pending-review"
            title="View collapse suggestions">×{post.numCollapseVotesPending}</a>

    // People should upvote any already existing suggestion, not create
    // new ones, so don't include any action link for creating a new suggestion,
    // if there is one already. Instead, show a link you can click to upvote
    // the existing suggestion:

    if ((!post.isTreeCollapsed && post.numCollapseTreeVotesPending == 0) ||
       (!post.isPostCollapsed && post.numCollapsePostVotesPending == 0))
      moreActionLinks ++= <a class="dw-a dw-a-collapse icon-collapse">Collapse</a>

    if (post.isTreeCollapsed && post.numUncollapseTreeVotesPending == 0)
      moreActionLinks ++= <a class="dw-a dw-a-uncollapse-tree">Uncollapse tree</a>

    if (post.isPostCollapsed && post.numUncollapsePostVotesPending == 0)
      moreActionLinks ++= <a class="dw-a dw-a-uncollapse-post">Uncollapse post</a>

    // ----- Move links

    // ? <a class="dw-a dw-a-move">Move</a>

    // ----- Delete links

    if (!post.isDeleted && post.numDeleteVotesPending > 0)
      suggestionsNew ++= <a class="dw-a dw-a-delete-suggs icon-trash dw-a-pending-review"
            title="View deletion suggestions">×{post.numDeleteVotesPending}</a>

    if (post.numDeleteTreeVotesPending == 0 || post.numDeletePostVotesPending == 0)
      moreActionLinks ++= <a class="dw-a dw-a-delete icon-trash">Delete</a>


    val renderActionsVertically = post.id == PageParts.BodyId // for now
    if (renderActionsVertically) {
      <div class="dw-p-as dw-as dw-p-as-hz">
        { replyLink }
        { rateLink }
        <a class="dw-a dw-a-more">More...</a>
        <span class="dw-p-as-more">
          { moreActionLinks }
        </span>
        { suggestionsNew }
        { suggestionsOld }
      </div>
    }
    else {
      // 1) Re the order of these links, see [bkfK20qE9] in debiki-play.css — there
      // are some selectors that assume the More... and Delete actions are the last
      // visible float-left-actions. (So don't move More... and `deleteLink`.)
      // 2) The suggestions float right. New not-yet-decided-on suggestions are always
      // visible, and are hence placed to the very right (they need to appear first in
      // the list below). Old suggestions are only shown when you hover the post with
      // the mouse (so as not to clutter the GUI) (ignore touch devices for now),
      // and are thus placed to the left of the new not-yet-decided-on suggestions.
      <div class="dw-p-as dw-as">
        {/* --- These float right --- */}
        { suggestionsNew }
        { suggestionsOld }
        {/* --- The rest float left --- */}
        { replyLink }
        { rateLink }
        <a class="dw-a dw-a-more">More...</a>
        <span class="dw-p-as-more">
          { moreActionLinks }
        </span>
      </div>
    }
  }


  def renderActionsForCollapsed(post: Post): Elem = {
    // Only show suggestions — don't show actions until the reader has
    // opened the comment and had a chance to read it.
    <div class="dw-p-as dw-as">
      { renderUncollapseSuggestions(post) }
    </div>
  }


  def renderActionsForDeleted(post: Post): Elem = {
    <div class="dw-p-as dw-as"></div> // for now
  }


  def renderUncollapseSuggestions(post: Post): NodeSeq = {
    var suggestions: NodeSeq = Nil
    if (post.isTreeCollapsed) {
      if (post.numUncollapseTreeVotesPending > 0)
        suggestions ++=
          <a class="dw-a dw-a-uncollapse-tree-suggs dw-a-pending-review"
              >Uncollapse × {post.numUncollapseTreeVotesPending}</a>
    }
    else if (post.isPostCollapsed) {
      // Only show these uncollapse-post actions if the whole *tree* is not already
      // collapsed (if it is, the post itself isn't visible at all).
      if (post.numUncollapsePostVotesPending > 0)
        suggestions ++=
          <a class="dw-a dw-a-uncollapse-post-suggs dw-a-pending-review"
              >Uncollapse × {post.numUncollapsePostVotesPending}</a>
    }

    suggestions
  }

}

