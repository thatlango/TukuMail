package org.tukutuku.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MailSession(val token:String,val email:String)
data class MailSummary(val id:String,val from:String,val subject:String,val preview:String,val receivedAt:String,val read:Boolean)
data class MailDetail(val id:String,val from:String,val to:List<String>,val cc:List<String>,val subject:String,val bodyText:String,val receivedAt:String,val read:Boolean)

class MailApi(private val base:String=BuildConfig.TUKUMAIL_API_BASE){
    suspend fun login(email:String,password:String)=withContext(Dispatchers.IO){ val j=request("/auth/session","POST",JSONObject().put("email",email).put("password",password)); MailSession(j.getString("token"),j.getString("email")) }
    suspend fun inbox(token:String)=withContext(Dispatchers.IO){ val a=requestArray("/mail/inbox?limit=75",token=token); (0 until a.length()).map{ i-> val j=a.getJSONObject(i); MailSummary(j.getString("id"),j.optString("from"),j.optString("subject"),j.optString("preview"),j.optString("receivedAt"),j.optBoolean("read")) } }
    suspend fun message(token:String,id:String)=withContext(Dispatchers.IO){ val j=request("/mail/messages/$id",token=token); MailDetail(j.getString("id"),j.optString("from"),strings(j.optJSONArray("to")),strings(j.optJSONArray("cc")),j.optString("subject"),j.optString("bodyText"),j.optString("receivedAt"),j.optBoolean("read")) }
    suspend fun send(token:String,to:String,cc:String,subject:String,body:String)=withContext(Dispatchers.IO){ val p=JSONObject().put("to",JSONArray(split(to))).put("cc",JSONArray(split(cc))).put("subject",subject).put("body",body); request("/mail/send","POST",p,token,true); Unit }
    private fun split(v:String)=v.split(',', ';').map{it.trim()}.filter{it.isNotEmpty()}
    private fun strings(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).map{a.optString(it)}
    private fun request(path:String,method:String="GET",body:JSONObject?=null,token:String?=null,allowEmpty:Boolean=false):JSONObject { val raw=execute(path,method,body,token); return if(allowEmpty||raw.isBlank()) JSONObject() else JSONObject(raw) }
    private fun requestArray(path:String,token:String)=JSONArray(execute(path,"GET",null,token))
    private fun execute(path:String,method:String,body:JSONObject?,token:String?):String { val c=(URL(base+path).openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=10000;readTimeout=15000;setRequestProperty("Accept","application/json");if(token!=null)setRequestProperty("Authorization","Bearer $token");if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json");outputStream.use{it.write(body.toString().toByteArray())}}}; val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream; val text=stream?.bufferedReader()?.use{it.readText()}.orEmpty(); c.disconnect(); if(code !in 200..299){val msg=runCatching{JSONObject(text).optString("error")}.getOrDefault("");throw IllegalStateException(msg.ifBlank{"Request failed ($code)"})}; return text }
}
