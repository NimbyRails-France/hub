#include "updater.h"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QProcess>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>
#include <QTimer>
#include <QUuid>
#include <QVersionNumber>
#include <memory>
namespace {
bool https(const QUrl& url){return url.isValid()&&url.scheme()=="https"&&!url.host().isEmpty()&&url.userInfo().isEmpty();}
QNetworkRequest request(const QUrl& url){QNetworkRequest r(url);r.setTransferTimeout(30000);r.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::NoLessSafeRedirectPolicy);return r;}
}
bool parseRelease(const QJsonObject& o,const QString& current,UpdateRelease& r){
 r={};r.version=o["version"].toString();r.url=QUrl(o["url"].toString());r.hash=o["sha256"].toString().toLatin1().toLower();
 const double size=o["size"].toDouble(-1);r.size=static_cast<qint64>(size>0&&size<=536870912?size:0);
 static const QRegularExpression version("^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}$"),hash("^[a-f0-9]{64}$");
 return o["schema"].toInt()==1&&o["product"].toString()=="NRFHub"&&o["platform"].toString()=="windows-x64"&&
  version.match(r.version).hasMatch()&&QVersionNumber::fromString(r.version)>QVersionNumber::fromString(current)&&
  https(r.url)&&hash.match(QString::fromLatin1(r.hash)).hasMatch()&&r.size>0&&double(r.size)==size;
}
Updater::Updater(QObject* parent):QObject(parent){
 QFile config(QCoreApplication::applicationDirPath()+"/hub-update.json");
 if(config.open(QIODevice::ReadOnly)&&config.size()<65536)feed_=QUrl(QJsonDocument::fromJson(config.readAll()).object()["feed"].toString());
 message(https(feed_)?QString("Hub %1").arg(QCoreApplication::applicationVersion()):QString("Mises à jour : serveur non configuré"));
 connect(QCoreApplication::instance(),&QCoreApplication::aboutToQuit,this,&Updater::installOnExit);
 auto* timer=new QTimer(this);connect(timer,&QTimer::timeout,this,&Updater::check);timer->start(6*60*60*1000);
 QTimer::singleShot(3000,this,&Updater::check);
}
void Updater::check(){
 if(!enabled_||busy_||ready())return;if(!https(feed_)){message("Mises à jour : serveur non configuré");return;}
 busy_=true;message("Recherche de mise à jour…");auto* reply=network_.get(request(feed_));
 auto bytes=std::make_shared<QByteArray>();
 connect(reply,&QIODevice::readyRead,this,[reply,bytes]{*bytes+=reply->readAll();if(bytes->size()>65536)reply->abort();});
 connect(reply,&QNetworkReply::finished,this,[this,reply,bytes]{
  *bytes+=reply->readAll();const bool ok=reply->error()==QNetworkReply::NoError&&bytes->size()<=65536&&https(reply->url());reply->deleteLater();busy_=false;
  if(!ok){message("Serveur de mises à jour indisponible");return;}
  const auto doc=QJsonDocument::fromJson(*bytes);UpdateRelease release;
  if(!doc.isObject()){message("Manifest de mise à jour invalide");return;}
  if(!parseRelease(doc.object(),QCoreApplication::applicationVersion(),release)){
   message(doc.object()["version"].toString()==QCoreApplication::applicationVersion()?"Hub et SDK à jour":"Mise à jour incompatible ou invalide");return;}
  download(release);
 });
}
void Updater::download(const UpdateRelease& release){
 const QString dir=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)+"/updates";
 if(!QDir().mkpath(dir)){message("Impossible de préparer la mise à jour");return;}
 const QString path=dir+"/setup-"+QUuid::createUuid().toString(QUuid::WithoutBraces)+".exe";
 auto file=std::make_shared<QSaveFile>(path);if(!file->open(QIODevice::WriteOnly)){message("Impossible de préparer la mise à jour");return;}
 auto hash=std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);auto size=std::make_shared<qint64>(0);
 busy_=true;message("Téléchargement du Hub et du SDK…");auto* reply=network_.get(request(release.url));
 connect(reply,&QIODevice::readyRead,this,[reply,file,hash,size,release]{
  const auto chunk=reply->readAll();*size+=chunk.size();
  if(*size>release.size||file->write(chunk)!=chunk.size()){reply->abort();return;}hash->addData(chunk);
 });
 connect(reply,&QNetworkReply::finished,this,[this,reply,file,hash,size,release,path]{
  const bool ok=reply->error()==QNetworkReply::NoError&&https(reply->url())&&*size==release.size&&hash->result().toHex()==release.hash;
  reply->deleteLater();busy_=false;
  if(!ok||!file->commit()){file->cancelWriting();message("Mise à jour rejetée : téléchargement incomplet ou empreinte incorrecte");return;}
  installer_=path;expectedHash_=release.hash;message("Mise à jour prête · installation à la fermeture");
 });
}
void Updater::restart(){if(ready()){relaunch_=true;QCoreApplication::quit();}}
void Updater::installOnExit(){
 if(!enabled_||!ready())return;
 QFile file(installer_);if(!file.open(QIODevice::ReadOnly))return;QCryptographicHash hash(QCryptographicHash::Sha256);
 if(!hash.addData(&file)||hash.result().toHex()!=expectedHash_)return;file.close();
 QStringList args{"/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART","/DIR="+QCoreApplication::applicationDirPath()};
 if(relaunch_)args<<"/RELAUNCH";
 QProcess::startDetached(installer_,args);
}
