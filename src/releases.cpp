#include "releases.h"
#include <QRegularExpression>
#include <QVersionNumber>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QDateTime>
#include <memory>

namespace {
const QRegularExpression versionPattern(R"(^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})(?:-(alpha|beta)\.([1-9][0-9]{0,8}))?$)");
QString releaseVersion(const QJsonObject& release){const auto tag=release["tag_name"].toString();return tag.startsWith('v')?tag.mid(1):QString();}
bool officialAsset(const QString& value,const QString& repo,const QString& tag){
 const QUrl url(value);
 const auto prefix="/NimbyRails-France/"+repo+"/releases/download/"+tag+"/";
 const auto path=url.path(QUrl::FullyDecoded);
 const auto file=path.mid(prefix.size());
 return url.scheme()=="https"&&url.host()=="github.com"&&url.userInfo().isEmpty()&&url.port()==-1&&url.query().isEmpty()&&url.fragment().isEmpty()&&
  path.startsWith(prefix)&&!file.isEmpty()&&!file.contains('/')&&!file.contains('\\')&&file!="."&&file!="..";
}
}
bool Releases::validVersion(const QString& version){return versionPattern.match(version).hasMatch();}
QString Releases::channel(const QString& version){auto match=versionPattern.match(version);return !match.hasMatch()?QString():match.captured(4).isEmpty()?"stable":match.captured(4);}
int Releases::compare(const QString& left,const QString& right){
 if(!validVersion(left)||!validVersion(right))return 0;
 const auto core=QVersionNumber::compare(QVersionNumber::fromString(left),QVersionNumber::fromString(right));if(core)return core;
 const auto l=versionPattern.match(left),r=versionPattern.match(right);
 auto rank=[](const QString& name){return name=="alpha"?0:name=="beta"?1:2;};
 const auto stage=rank(channel(left))-rank(channel(right));if(stage)return stage;
 return l.captured(5).toInt()-r.captured(5).toInt();
}
QString Releases::selectedChannel(const QJsonObject& settings,const QString& project){
 const auto name=settings[project].toString();return name=="alpha"||name=="beta"?name:"stable";
}
QJsonObject Releases::select(const QJsonArray& releases,const QString& selected,const QString& manifest){
 if(selected!="stable"&&selected!="alpha"&&selected!="beta")return {};
 QJsonObject best;
 for(const auto value:releases){
  const auto release=value.toObject();const auto version=releaseVersion(release);
  if(release["draft"].toBool()||release["published_at"].toString().isEmpty()||channel(version)!=selected||release["prerelease"].toBool()!=(selected!="stable"))continue;
  bool found=false;for(const auto asset:release["assets"].toArray())if(asset.toObject()["name"]==manifest&&asset.toObject()["state"]=="uploaded")found=true;
  if(found&&(best.isEmpty()||compare(version,releaseVersion(best))>0))best=release;
 }
 return best;
}
QString Releases::assetUrl(const QJsonObject& release,const QString& repo,const QString& name){
 for(const auto value:release["assets"].toArray()){
  const auto asset=value.toObject();const auto url=asset["browser_download_url"].toString();
  if(asset["name"]==name&&asset["state"]=="uploaded"&&officialAsset(url,repo,release["tag_name"].toString()))return url;
 }return {};
}
bool Releases::matchesAsset(const QJsonObject& manifest,const QJsonObject& release,const QString& repo){
 if(manifest["version"].toString()!=releaseVersion(release)||!validVersion(manifest["version"].toString()))return false;
 if(manifest.contains("channel")&&manifest["channel"].toString()!=channel(manifest["version"].toString()))return false;
 const auto url=manifest["url"].toString();if(!officialAsset(url,repo,release["tag_name"].toString()))return false;
 for(const auto value:release["assets"].toArray()){
  const auto asset=value.toObject();
  if(asset["browser_download_url"]!=url||asset["state"]!="uploaded"||asset["size"].toDouble()!=manifest["size"].toDouble())continue;
  const auto digest=asset["digest"].toString();
  return digest.isEmpty()||digest.compare("sha256:"+manifest["sha256"].toString(),Qt::CaseInsensitive)==0;
 }return false;
}
ReleaseClient::ReleaseClient(QObject* parent):QObject(parent){}
void ReleaseClient::get(const QUrl& url,int limit,std::function<void(QByteArray,QString)> done){
 if(QDateTime::currentSecsSinceEpoch()<retryAfter_){done({},"Limite GitHub atteinte ; réessayez après "+QDateTime::fromSecsSinceEpoch(retryAfter_).toLocalTime().toString("HH:mm"));return;}
 const auto key=url.toString();QNetworkRequest request(url);request.setTransferTimeout(30000);
 request.setRawHeader("User-Agent","NimbyRailsFrance-Hub");
 request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::NoLessSafeRedirectPolicy);
 if(url.host()=="api.github.com"){
  request.setRawHeader("Accept","application/vnd.github+json");request.setRawHeader("X-GitHub-Api-Version","2022-11-28");
  if(cache_.contains(key))request.setRawHeader("If-None-Match",cache_[key].etag);
 }
 auto* reply=network_.get(request);auto bytes=std::make_shared<QByteArray>();
 connect(reply,&QIODevice::readyRead,this,[reply,bytes,limit]{*bytes+=reply->readAll();if(bytes->size()>limit)reply->abort();});
 connect(reply,&QNetworkReply::finished,this,[this,reply,bytes,key,limit,done]{
  *bytes+=reply->readAll();const auto status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  const auto error=reply->error();const auto etag=reply->rawHeader("ETag");const auto reset=reply->rawHeader("X-RateLimit-Reset").toLongLong();
  const bool https=reply->url().scheme()=="https";reply->deleteLater();
  if(status==304&&cache_.contains(key)){done(cache_[key].body,{});return;}
  if(status==429||status==403){retryAfter_=qMax(QDateTime::currentSecsSinceEpoch()+300,reset);done({},"GitHub refuse temporairement la requête (HTTP "+QString::number(status)+") ; nouvelle tentative après "+QDateTime::fromSecsSinceEpoch(retryAfter_).toLocalTime().toString("HH:mm"));return;}
  if(error!=QNetworkReply::NoError||status!=200||!https||bytes->size()>limit){done({},"Release indisponible (HTTP "+QString::number(status)+") ou réponse invalide");return;}
  if(!etag.isEmpty())cache_[key]={etag,*bytes};done(*bytes,{});
 });
}
void ReleaseClient::pages(const QString& path,int page,QJsonArray rows,std::function<void(QJsonArray,QString)> done){
 if(page>20){done({},"Trop de pages GitHub ; recherche interrompue sans sélectionner une version incomplète");return;}
 get(QUrl("https://api.github.com/"+path+"per_page=100&page="+QString::number(page)),4*1024*1024,[this,path,page,rows,done](QByteArray bytes,QString error)mutable{
  if(!error.isEmpty()){done({},error);return;}const auto doc=QJsonDocument::fromJson(bytes);if(!doc.isArray()){done({},"Réponse GitHub invalide");return;}
  const auto batch=doc.array();for(const auto row:batch)rows.append(row);
  if(batch.size()==100)pages(path,page+1,rows,done);else done(rows,{});
 });
}
void ReleaseClient::discover(std::function<void(QStringList,QString)> done){
 if(QDateTime::currentSecsSinceEpoch()<discoverUntil_){done(repositories_,{});return;}
 pages("orgs/NimbyRails-France/repos?type=public&",1,{},[this,done](QJsonArray rows,QString error){
  if(!error.isEmpty()){done({},error);return;}QStringList repos;
  for(const auto value:rows){const auto r=value.toObject();const auto name=r["name"].toString();
   if(r["owner"].toObject()["login"].toString().compare("NimbyRails-France",Qt::CaseInsensitive)!=0||r["archived"].toBool()||r["disabled"].toBool()||r["fork"].toBool())continue;
   if(name=="hub"||name=="website"||name=="nimbyrailsfrance-bot"||!QRegularExpression("^[a-z][a-z0-9-]{0,63}$").match(name).hasMatch())continue;
   repos.append(name);
  }repos.sort();if(repos.removeOne("sdk"))repos.prepend("sdk");repositories_=repos;discoverUntil_=QDateTime::currentSecsSinceEpoch()+3600;done(repos,{});
 });
}
void ReleaseClient::fetch(const QString& repo,const QString& selected,const QString& manifest,Result done){
 if(!QRegularExpression("^[a-z][a-z0-9-]{0,63}$").match(repo).hasMatch()){done({},"Dépôt invalide");return;}
 pages("repos/NimbyRails-France/"+repo+"/releases?",1,{},[this,repo,selected,manifest,done](QJsonArray rows,QString error){
  if(!error.isEmpty()){done({},error);return;}
  const auto release=Releases::select(rows,selected,manifest);
  if(release.isEmpty()){done({},"Aucune release installable sur le canal "+selected);return;}
  const auto url=Releases::assetUrl(release,repo,manifest);if(url.isEmpty()){done({},"Manifeste absent ou non officiel");return;}
  get(QUrl(url),65536,[repo,selected,release,done](QByteArray bytes,QString error){
   if(!error.isEmpty()){done({},error);return;}const auto doc=QJsonDocument::fromJson(bytes);
   if(!doc.isObject()||!Releases::matchesAsset(doc.object(),release,repo)){done({},"Le manifeste ne correspond pas aux fichiers de cette release");return;}
   auto result=doc.object();result["channel"]=selected;result["releaseUrl"]="https://github.com/NimbyRails-France/"+repo+"/releases/tag/"+release["tag_name"].toString();result["changelog"]=release["body"].toString();done(result,{});
  });
 });
}
