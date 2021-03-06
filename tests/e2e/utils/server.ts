// This file sends requests to the server we're testing. Doesn't start any server.

/// <reference path="../test-types.ts"/>

import _ = require('lodash');
import settings = require('./settings');
import utils = require('./utils');
import { logMessage, die, dieIf } from './log-and-die';

// Didn't find any Typescript defs.
declare function require(path: string): any;
const syncRequest = require('sync-request');

let xsrfTokenAndCookies;


function initOrDie() {
  const response = syncRequest('GET', settings.mainSiteOrigin);
  dieIf(response.statusCode !== 200,
      "Error getting xsrf token and cookies from " + settings.mainSiteOrigin + " [EsE2FKE3]",
      showResponse(response));

  let cookieString = '';
  let xsrfToken = '';
  const cookies = response.headers['set-cookie'];
  _.each(cookies, function(cookie) {
    // A Set-Cookie header value looks like so: "name=value; options"
    const nameValueStr = cookie.split(';')[0];
    const nameAndValue = nameValueStr.split('=');
    const name = nameAndValue[0];
    const value = nameAndValue[1];
    cookieString += nameValueStr + '; ';
    if (name == 'XSRF-TOKEN') {
      xsrfToken = value;
    }
  });
  dieIf(!xsrfToken, "Got no xsrf token from " + settings.mainSiteOrigin + " [EsE8GLK2]");
  xsrfTokenAndCookies = [xsrfToken, cookieString];
}


function postOrDie(url, data) {
  dieIf(!settings.e2eTestPassword, "No E2E test password specified [EsE2WKG4]");
  logMessage('POST ' + url + ' ...  [EsM5JMMK2]');

  const passwordParam =
      (url.indexOf('?') === -1 ? '?' : '&') + 'e2eTestPassword=' + settings.e2eTestPassword;

  const headers = !xsrfTokenAndCookies ? {} : {
    'X-XSRF-TOKEN': xsrfTokenAndCookies[0],
    'Cookie': xsrfTokenAndCookies[1]
  };

  const response = syncRequest('POST', url + passwordParam, { json: data, headers: headers });

  dieIf(response.statusCode !== 200, "POST request failed to " + url + " [EsE5GPK02]",
      showResponse(response));
  return {
    statusCode: response.statusCode,
    headers: response.headers,
    bodyJson: function() {
      return JSON.parse(response.getBody('utf8'));
    }
  };
}


function getOrDie(url) {
  dieIf(!settings.e2eTestPassword, "No E2E test password specified [EsE2KU603]");
  logMessage('GET ' + url);

  const passwordParam =
      (url.indexOf('?') === -1 ? '?' : '&') + 'e2eTestPassword=' + settings.e2eTestPassword;

  const headers = !xsrfTokenAndCookies ? {} : {
    'X-XSRF-TOKEN': xsrfTokenAndCookies[0],
    'Cookie': xsrfTokenAndCookies[1]
  };

  const response = syncRequest('GET', url + passwordParam, { headers: headers });

  dieIf(response.statusCode !== 200, "GET request failed to " + url + " [EsE8JYT4]",
      showResponse(response));
  return response;
}


function showResponse(response) {
  let bodyString = response.body;
  if (!_.isString(bodyString) && bodyString.toString) {
    bodyString = bodyString.toString('utf8');
  }
  if (!_.isString(bodyString)) {
    bodyString = "(The response body is not a string, and has no toString function. " +
        "Don't know how to show it. [EdE7BXE2I])"
  }
  return (
      "Response status code: " + response.statusCode + " (should have been 200)\n" +
      showResponseBodyJson(bodyString));
}


function showResponseBodyJson(body) {
  let text = body;
  if (!_.isString(text)) text = JSON.stringify(text);
  return (
  "Response body: ———————————————————————————————————————————————————————————————————\n" +
  text +
  "\n——————————————————————————————————————————————————————————————————————————————————\n");
}


function importSiteData(siteData: SiteData): IdAddress {
  const deleteOldSite = settings.deleteOldSite ? '?deleteOldSite=true' : '';
  const url = settings.mainSiteOrigin + '/-/import-test-site' + deleteOldSite;
  const ids = postOrDie(url, siteData).bodyJson();
  dieIf(!ids.id, "No site id in import-site response [EsE7UGK2]",
      showResponseBodyJson(ids));
  return ids;
}


function deleteOldTestSite(localHostname: string) {
  postOrDie(settings.mainSiteOrigin + '/-/delete-test-site', { localHostname });
}


function playTimeSeconds(seconds: number) {
  const url = settings.mainSiteOrigin + '/-/play-time';
  postOrDie(url, { seconds: seconds });
}


function playTimeMinutes(minutes: number) { playTimeSeconds(minutes * 60); }
function playTimeHours(hours: number) { playTimeSeconds(hours * 3600); }
function playTimeDays(days: number) { playTimeSeconds(days * 3600 * 24); }


function getLastEmailSenTo(siteId: SiteId, email: string, browser): EmailSubjectBody | null {
  for (let attemptNr = 1; attemptNr <= settings.waitforTimeout / 500; ++attemptNr) {
    const response = getOrDie(settings.mainSiteOrigin + '/-/last-e2e-test-email?sentTo=' + email +
      '&siteId=' + siteId);
    const lastEmails = JSON.parse(response.body);
    if (lastEmails.length)
      return lastEmails[lastEmails.length - 1];
    // Internal functions can pass null, if they pause themselves.
    if (browser) {
      browser.pause(500 - 100); // 100 ms for a request, perhaps?
    }
    else {
      return null;
    }
  }
  die(`Timeout in getLastEmailSenTo, address: ${email} [EdE5JSRWG0]`)
}


/** Doesn't count all emails, only the last 10? so after many emails sent, becomes useless.
 */
function countLastEmailsSentTo(siteId: SiteId, email: string): number {
  const response = getOrDie(settings.mainSiteOrigin + '/-/last-e2e-test-email?sentTo=' + email +
    '&siteId=' + siteId + '&timeoutMs=1000');
  const lastEmails = JSON.parse(response.body);
  return lastEmails.length;
}


function getLastVerifyEmailAddressLinkEmailedTo(siteId: SiteId, emailAddress: string,
      browser?): string {
  const email = getLastEmailSenTo(siteId, emailAddress, browser);
  return utils.findFirstLinkToUrlIn('https?://.*/-/login-password-confirm-email', email.bodyHtmlText);
}


function waitAndGetVerifyAnotherEmailAddressLinkEmailedTo(siteId: SiteId, emailAddress: string,
      browser?): string {
  waitUntilLastEmailMatches(
    siteId, emailAddress, ["To finish adding", /* [B4FR20L_] */ emailAddress], browser);
  const email = getLastEmailSenTo(siteId, emailAddress, browser);
  return utils.findFirstLinkToUrlIn('https?://.*/-/confirm-email-address', email.bodyHtmlText);
}


const unsubUrlRegexString = 'https?://.*/-/unsubscribe';

function getLastUnsubscriptionLinkEmailedTo(siteId: SiteId, emailAddress: string, browser): string {
  const email = getLastEmailSenTo(siteId, emailAddress, browser);
  return utils.findFirstLinkToUrlIn(unsubUrlRegexString, email.bodyHtmlText);
}


function getAnyUnsubscriptionLinkEmailedTo(siteId: SiteId, emailAddress: string, browser?): string {
  const email = getLastEmailSenTo(siteId, emailAddress, browser);
  return utils.findAnyFirstLinkToUrlIn(unsubUrlRegexString, email.bodyHtmlText);
}


function waitForUnsubscriptionLinkEmailedTo(siteId: SiteId, emailAddress: string, browser): string {
  for (let attemptNr = 1; attemptNr <= settings.waitforTimeout / 500; ++attemptNr) {
    const email = getLastEmailSenTo(siteId, emailAddress, null);
    const link = !email ? null : utils.findAnyFirstLinkToUrlIn(unsubUrlRegexString, email.bodyHtmlText);
    if (!link)
      browser.pause(500 - 100); // 100 ms for a request, perhaps?
    else
      return link;
  }
}


function waitUntilLastEmailMatches(siteId: SiteId, emailAddress: string,
        textOrTextsToMatch: string | string[], browser): string | string[] {
  const textsToMatch: string[] =
      _.isString(textOrTextsToMatch) ? [textOrTextsToMatch] : textOrTextsToMatch;
  const regexs = textsToMatch.map(text => new RegExp(utils.regexEscapeSlashes(text)));
  let misses: string[];
  for (let attemptNr = 1; attemptNr <= settings.waitforTimeout / 500; ++attemptNr) {
    const email = getLastEmailSenTo(siteId, emailAddress, null);
    misses = [];
    let matchingStrings: string[] = [];
    for (let i = 0; i < regexs.length; ++i) {
      const regex = regexs[i];
      const matches = !email ? null : email.bodyHtmlText.match(regex);
      if (matches) {
        matchingStrings.push(matches[0]);
      }
      else {
        misses.push(textsToMatch[i]);
      }
    }
    if (!misses.length)
      return _.isString(textOrTextsToMatch) ? matchingStrings[0] : matchingStrings;
    browser.pause(500 - 100);
  }
  const missesString = misses.join(', ');
  die(`Never got any email to ${emailAddress} matching ${missesString} [EdE5JGK2Q1]`);
}


function lastEmailMatches(siteId: SiteId, emailAddress: string,
      textOrTextsToMatch: string | string[], browser): string | false {
  const textsToMatch: string[] =
    _.isString(textOrTextsToMatch) ? [textOrTextsToMatch] : textOrTextsToMatch;
  const regexs = textsToMatch.map(text => new RegExp(utils.regexEscapeSlashes(text)));
  const email = getLastEmailSenTo(siteId, emailAddress, browser);
  for (let i = 0; i < regexs.length; ++i) {
    const regex = regexs[i];
    const matches = email.bodyHtmlText.match(regex);
    if (matches) {
      return matches[0];
    }
  }
  return false;
}


export = {
  initOrDie,
  importSiteData,
  deleteOldTestSite,
  playTimeSeconds,
  playTimeMinutes,
  playTimeHours,
  playTimeDays,
  getLastEmailSenTo,
  countLastEmailsSentTo,
  getLastVerifyEmailAddressLinkEmailedTo,
  waitAndGetVerifyAnotherEmailAddressLinkEmailedTo,
  getLastUnsubscriptionLinkEmailedTo,
  getAnyUnsubscriptionLinkEmailedTo,
  waitForUnsubscriptionLinkEmailedTo,
  waitUntilLastEmailMatches,
  lastEmailMatches,
};

