#include "updater.h"
#include "releases.h"
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
#include <QDateTime>
#include <QUrlQuery>
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
  Releases::validVersion(r.version)&&Releases::compare(r.version,current)>0&&
  https(r.url)&&r.url.host()=="github.com"&&r.url.path().startsWith("/NimbyRails-France/hub/releases/download/")&&hash.match(QString::fromLatin1(r.hash)).hasMatch()&&r.size>0&&double(r.size)==size;
}
Updater::Updater(QObject* parent):QObject(parent),releases_(new ReleaseClient(this)){
 message(QString("Hub %1 · stable").arg(QCoreApplication::applicationVersion()));
 connect(QCoreApplication::instance(),&QCoreApplication::aboutToQuit,this,&Updater::installOnExit);
 auto* timer=new QTimer(this);connect(timer,&QTimer::timeout,this,[this]{check();});timer->start(6*60*60*1000);
 if(!QCoreApplication::arguments().contains("--ui-test"))QTimer::singleShot(3000,this,[this]{check();});
}
void Updater::setChannel(const QString& channel){
 const auto selected=Releases::selectedChannel({{"hub",channel}},"hub");if(selected==channel_)return;
 ++generation_;channel_=selected;busy_=false;canSwitch_=false;manualInstall_=false;
 if(!installer_.isEmpty())QFile::remove(installer_);installer_.clear();expectedHash_.clear();
 message("Hub · canal "+channel_);
}
void Updater::check(bool manual,bool allowOlder){
 if((!enabled_&&!manual)||busy_||ready())return;
 busy_=true;canSwitch_=false;const auto generation=generation_;message("Recherche du Hub · "+channel_+"…");
 releases_->fetch("hub",channel_,"hub-latest.json",[this,generation,manual,allowOlder](QJsonObject manifest,QString error){
  if(generation!=generation_)return;busy_=false;
  if(!error.isEmpty()){message(error);return;}UpdateRelease release;
  if(!parseRelease(manifest,"0.0.0-alpha.1",release)){message("Manifeste du Hub invalide");return;}
  const int order=Releases::compare(release.version,QCoreApplication::applicationVersion());
  if(order==0){message("Hub à jour · "+channel_);return;}
  if(order<0&&!allowOlder){canSwitch_=true;message("Canal "+channel_+" : "+release.version+" · retour manuel uniquement");return;}
  manualInstall_=manual;download(release);
 });
}
void Updater::download(const UpdateRelease& release){
 const QString dir=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)+"/updates";
 if(!QDir().mkpath(dir)){message("Impossible de préparer la mise à jour");return;}
 const QString path=dir+"/setup-"+QUuid::createUuid().toString(QUuid::WithoutBraces)+".exe";
 auto file=std::make_shared<QSaveFile>(path);if(!file->open(QIODevice::WriteOnly)){message("Impossible de préparer la mise à jour");return;}
 auto hash=std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);auto size=std::make_shared<qint64>(0);
 const auto generation=generation_;busy_=true;message("Téléchargement du Hub…");auto* reply=network_.get(request(release.url));
 connect(reply,&QIODevice::readyRead,this,[reply,file,hash,size,release]{
  const auto chunk=reply->readAll();*size+=chunk.size();
  if(*size>release.size||file->write(chunk)!=chunk.size()){reply->abort();return;}hash->addData(chunk);
 });
 connect(reply,&QNetworkReply::finished,this,[this,reply,file,hash,size,release,path,generation]{
  const bool ok=reply->error()==QNetworkReply::NoError&&https(reply->url())&&*size==release.size&&hash->result().toHex()==release.hash;
  reply->deleteLater();if(generation!=generation_){file->cancelWriting();return;}busy_=false;
  if(!ok||!file->commit()){file->cancelWriting();message("Mise à jour rejetée : téléchargement incomplet ou empreinte incorrecte");return;}
  installer_=path;expectedHash_=release.hash;message("Mise à jour prête · cliquez sur Redémarrer le Hub");
 });
}
void Updater::restart(){if(ready()){relaunch_=true;QCoreApplication::quit();}}
void Updater::installOnExit(){
 if((!enabled_&&!manualInstall_)||!ready())return;
 QFile file(installer_);if(!file.open(QIODevice::ReadOnly))return;QCryptographicHash hash(QCryptographicHash::Sha256);
 if(!hash.addData(&file)||hash.result().toHex()!=expectedHash_)return;file.close();
 QStringList args{"/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART","/DIR="+QCoreApplication::applicationDirPath()};
 if(relaunch_)args<<"/RELAUNCH";
 QProcess::startDetached(installer_,args);
}
