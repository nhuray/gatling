/**
 * Copyright 2011 eBusiness Information, Groupe Excilys (www.excilys.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.http.request.builder.api
import com.excilys.ebi.gatling.http.action.HttpRequestActionBuilder
import com.excilys.ebi.gatling.core.context.Context
import com.excilys.ebi.gatling.http.request.HttpRequest
import com.ning.http.client.RequestBuilder
import com.ning.http.client.Request
import com.ning.http.client.Realm
import com.ning.http.client.FluentCaseInsensitiveStringsMap
import com.ning.http.client.FluentStringsMap
import com.excilys.ebi.gatling.http.config.HttpProtocolConfiguration._
import com.excilys.ebi.gatling.http.config.HttpProtocolConfiguration
import com.excilys.ebi.gatling.http.util.HttpHelper._
import scala.collection.immutable.HashMap
import com.ning.http.client.Cookie
import com.ning.http.client.Realm.AuthScheme
import com.excilys.ebi.gatling.http.Predef._
import com.excilys.ebi.gatling.http.check.HttpCheckBuilder

object AbstractHttpRequestBuilder {
	implicit def toHttpRequestActionBuilder(requestBuilder: AbstractHttpRequestBuilder[_]) =
		requestBuilder.httpRequestActionBuilder withRequest (new HttpRequest(requestBuilder.httpRequestActionBuilder.requestName, requestBuilder))
}
abstract class AbstractHttpRequestBuilder[B <: AbstractHttpRequestBuilder[B]](val httpRequestActionBuilder: HttpRequestActionBuilder, method: String, urlFunction: Context => String,
		queryParams: List[(Context => String, Context => String)], headers: Map[String, String], followsRedirects: Option[Boolean], credentials: Option[(String, String)]) {

	def newInstanceWithQueryParam(paramKeyFunc: Context => String, paramValueFunc: Context => String): B with HttpRequestBuilderQueryParam

	def newInstanceWithHeaders(givenHeaders: Map[String, String]): B with HttpRequestBuilderHeader

	def newInstanceWithHeader(header: (String, String)): B with HttpRequestBuilderContentType

	def newInstanceWithContentType(mimeType: String): B with HttpRequestBuilderOptions

	def newInstanceWithFollowsRedirect(followRedirect: Boolean): B

	def newInstanceWithCredentials(username: String, password: String): B

	/**
	 * Stops defining the request and adds checks on the response
	 *
	 * @param checkBuilders the checks that will be performed on the reponse
	 */
	def check(checkBuilders: HttpCheckBuilder[_]*) = httpRequestActionBuilder withRequest (new HttpRequest(httpRequestActionBuilder.requestName, this)) withProcessors checkBuilders

	/**
	 * This method actually fills the request builder to avoid race conditions
	 *
	 * @param context the context of the current scenario
	 */
	def getRequestBuilder(context: Context): RequestBuilder = {
		val requestBuilder = new RequestBuilder
		requestBuilder setMethod method setFollowRedirects followsRedirects.getOrElse(false)

		addURLTo(requestBuilder, context)
		addProxyTo(requestBuilder, context)
		addCookiesTo(requestBuilder, context)
		addQueryParamsTo(requestBuilder, context)
		addHeadersTo(requestBuilder, headers)
		addAuthenticationTo(requestBuilder, credentials)

		requestBuilder
	}

	/**
	 * This method adds proxy information to the request builder if needed
	 *
	 * @param requestBuilder the request builder to which the proxy should be added
	 * @param context the context of the current scenario
	 */
	private def addProxyTo(requestBuilder: RequestBuilder, context: Context) = {
		val httpConfiguration = context.getProtocolConfiguration(HTTP_PROTOCOL_TYPE).map { configuration =>
			configuration.asInstanceOf[HttpProtocolConfiguration]
		}

		if (httpConfiguration.isDefined)
			httpConfiguration.get.proxy.map { proxy => requestBuilder.setProxyServer(proxy) }
	}

	/**
	 * This method adds the url to the request builder. It does so by applying the urlFunction to the current context
	 *
	 * @param requestBuilder the request builder to which the url should be added
	 * @param context the context of the current scenario
	 */
	private def addURLTo(requestBuilder: RequestBuilder, context: Context) = {
		val urlProvided = urlFunction(context)

		val httpConfiguration = context.getProtocolConfiguration(HTTP_PROTOCOL_TYPE).map { configuration =>
			configuration.asInstanceOf[HttpProtocolConfiguration]
		}

		// baseUrl implementation
		val url =
			if (urlProvided.startsWith("http"))
				urlProvided
			else if (httpConfiguration.isDefined)
				httpConfiguration.get.baseURL.map {
					baseUrl => baseUrl + urlProvided
				}.getOrElse(urlProvided)
			else
				throw new IllegalArgumentException("URL is invalid (does not start with http): " + urlProvided)

		requestBuilder.setUrl(url)
	}

	/**
	 * This method adds the cookies of last response to the request builder
	 *
	 * @param requestBuilder the request builder to which the cookies should be added
	 * @param context the context of the current scenario
	 */
	private def addCookiesTo(requestBuilder: RequestBuilder, context: Context) = {
		for ((cookieName, cookie) <- context.getAttributeAsOption(COOKIES_CONTEXT_KEY).getOrElse(HashMap.empty).asInstanceOf[HashMap[String, Cookie]]) {
			requestBuilder.addOrReplaceCookie(cookie)
		}
	}

	/**
	 * This method adds the query parameters to the request builder
	 *
	 * @param requestBuilder the request builder to which the query parameters should be added
	 * @param context the context of the current scenario
	 */
	private def addQueryParamsTo(requestBuilder: RequestBuilder, context: Context) = {
		val queryParamsMap = new FluentStringsMap

		val keyValues = for ((keyFunction, valueFunction) <- queryParams) yield (keyFunction.apply(context), valueFunction.apply(context))

		keyValues.groupBy(entry => entry._1).foreach { entry =>
			val (key, values) = entry
			queryParamsMap.add(key, values.map { value => value._2 }: _*)
		}

		if (!queryParamsMap.isEmpty)
			requestBuilder setQueryParameters queryParamsMap
	}

	/**
	 * This method adds the headers to the request builder
	 *
	 * @param requestBuilder the request builder to which the headers should be added
	 * @param context the context of the current scenario
	 */
	private def addHeadersTo(requestBuilder: RequestBuilder, headers: Map[String, String]) = {
		requestBuilder setHeaders (new FluentCaseInsensitiveStringsMap)
		for (header <- headers) { requestBuilder addHeader (header._1, header._2) }
	}

	/**
	 * This method adds authentication to the request builder if needed
	 *
	 * @param requestBuilder the request builder to which the credentials should be added
	 * @param credentials the credentials to put in the request builder
	 */
	private def addAuthenticationTo(requestBuilder: RequestBuilder, credentials: Option[(String, String)]) = {
		credentials.map { c =>
			val (username, password) = c
			val realm = new Realm.RealmBuilder().setPrincipal(username).setPassword(password).setUsePreemptiveAuth(true).setScheme(AuthScheme.BASIC).build
			requestBuilder.setRealm(realm)
		}
	}

	/**
	 * This method builds the request that will be sent
	 *
	 * @param context the context of the current scenario
	 */
	def build(context: Context): Request = getRequestBuilder(context) build

}