#include "hub.h"
#include "updater.h"
#include "releases.h"
#include <QComboBox>
#include <QApplication>
#include <QMenu>
#include <QMenuBar>
#include <QPainter>
#include <QShortcut>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QVersionNumber>
#include <QDateTime>
#include <memory>
#include <cstdio>
#include <QFile>
#include <QStandardPaths>

namespace {
constexpr auto pushUrl="https://ntfy.sh/nrf-hub-releases-v1-67e49b30/json";
}

void Hub::showHub(){
 if(isMinimized())setWindowState(windowState()&~Qt::WindowMinimized);
 show();raise();activateWindow();
}
void Hub::toggleFullscreen(){
 if(isFullScreen()){
  // On Windows, clear fullscreen before restoring the maximized state.
  showNormal();if(beforeFullscreen_.testFlag(Qt::WindowMaximized))showMaximized();
 }
 else {beforeFullscreen_=windowState()&~Qt::WindowMinimized;showFullScreen();}
 showHub();
}
void Hub::quitHub(){
 if(busy_){showHub();append("Veuillez attendre la fin des opérations avant de quitter.");return;}
 save();QApplication::quit();
}
void Hub::notify(const QString& title,const QString& message){
 if(tray_&&tray_->isVisible())tray_->showMessage(title,message,QSystemTrayIcon::Information,8000);
}
void Hub::setupDesktop(){
 QPixmap icon(64,64);icon.fill(Qt::transparent);QPainter painter(&icon);
 painter.setRenderHint(QPainter::Antialiasing);painter.setBrush(QColor("#183746"));painter.setPen(Qt::NoPen);painter.drawRoundedRect(0,0,64,64,12,12);
 painter.setPen(QPen(QColor("#75dfc5"),5));painter.drawLine(22,12,22,52);painter.drawLine(42,12,42,52);
 for(int y:{20,32,44})painter.drawLine(17,y,47,y);painter.end();setWindowIcon(QIcon(icon));
 auto* window=menuBar()->addMenu("Fenêtre");
 window->addAction("Réduire",this,[this]{showMinimized();});
 window->addAction("Plein écran / Fenêtre",QKeySequence(Qt::Key_F11),this,[this]{toggleFullscreen();});
 auto* escape=new QShortcut(QKeySequence(Qt::Key_Escape),this);connect(escape,&QShortcut::activated,this,[this]{if(isFullScreen())toggleFullscreen();});
 window->addAction("Quitter le Hub",this,[this]{quitHub();});
 if(QSystemTrayIcon::isSystemTrayAvailable()){
  tray_=new QSystemTrayIcon(windowIcon(),this);tray_->setToolTip("NimbyRails France Hub · mises à jour actives");
  auto* menu=new QMenu(this);menu->addAction("Afficher le Hub",this,[this]{showHub();});
  menu->addAction("Vérifier les mises à jour",this,[this]{refresh();selfUpdate_->check();});
  menu->addAction("Plein écran / Fenêtre",this,[this]{toggleFullscreen();});menu->addSeparator();
  menu->addAction("Quitter",this,[this]{quitHub();});tray_->setContextMenu(menu);
  connect(tray_,&QSystemTrayIcon::activated,this,[this](auto reason){if(reason==QSystemTrayIcon::Trigger||reason==QSystemTrayIcon::DoubleClick)showHub();});
  connect(tray_,&QSystemTrayIcon::messageClicked,this,[this]{showHub();});tray_->show();
  qApp->setQuitOnLastWindowClosed(false);
  window->addAction("Masquer dans la zone de notification",this,[this]{save();hide();});
 }
 connect(selfUpdate_,&Updater::changed,this,[this]{if(selfUpdate_->ready()&&!selfNotified_){selfNotified_=true;notify("Mise à jour du Hub prête","Ouvrez le Hub puis cliquez sur Redémarrer pour appliquer la mise à jour.");}});
 pushStatus_=new QLabel("Notifications en direct : connexion…");menuBar()->setCornerWidget(pushStatus_);
 pushDebounce_=new QTimer(this);pushDebounce_->setSingleShot(true);
 connect(pushDebounce_,&QTimer::timeout,this,[this]{
  if(busy_){pushDebounce_->start(5000);return;}
  pushThrottle_.restart();append("Événement reçu : vérification des releases officielles…");refresh();selfUpdate_->check();
 });
 if(!qApp->arguments().contains("--ui-test"))QTimer::singleShot(0,this,[this]{connectPush();});
}

void Hub::schedulePushCheck(){
 // Relay events are untrusted wake-up hints, never download instructions.
 // Coalesce bursts and keep a maximum of one check per five minutes.
 if(pushDebounce_->isActive())return;
 const auto delay=pushThrottle_.isValid()?qMax<qint64>(2000,300000-pushThrottle_.elapsed()):2000;
 pushDebounce_->start(int(delay));
}
void Hub::connectPush(){
 QNetworkRequest request{QUrl(pushUrl)};request.setTransferTimeout(90000);
 auto* reply=network_.get(request);reply->setReadBufferSize(65536);
 auto pending=std::make_shared<QByteArray>();
 connect(reply,&QIODevice::readyRead,this,[this,reply,pending]{
  *pending+=reply->readAll();
  while(true){const auto end=pending->indexOf('\n');if(end<0)break;
   const auto event=QJsonDocument::fromJson(pending->left(end)).object();pending->remove(0,end+1);
   if(event["event"]=="open"){reconnectMs_=2000;pushStatus_->setText("Notifications en direct : connectées");}
   else if(event["event"]=="message"){++pushMessages_;schedulePushCheck();}
  }
  if(pending->size()>65536)reply->abort();
 });
 connect(reply,&QNetworkReply::finished,this,[this,reply]{
  reply->deleteLater();pushStatus_->setText("Direct déconnecté · vérification toutes les 15 min");
  QTimer::singleShot(reconnectMs_,this,[this]{connectPush();});reconnectMs_=qMin(reconnectMs_*2,300000);
 });
}

void Hub::refreshReleases(int index){
 if(index>=repositories_.size()){setBusy(false);detectGame();render();append("Releases vérifiées · "+QString::number(verifiedReleases_)+" manifestes reçus");notifyUpdates();checkUpdates();return;}
 const auto id=repositories_[index];const auto channel=Releases::selectedChannel(channels_,id);
 releases_->fetch(id,channel,"project.json",[this,index,id,channel](QJsonObject p,QString error){
  if(error.isEmpty()&&(!validProject(p)||p["id"]!=id||(id=="sdk"&&p["kind"]!="sdk")||(id=="tco"&&p["kind"]!="tco")||(id!="sdk"&&p["kind"]=="sdk")))error="Manifeste du projet invalide";
  if(error.isEmpty()){++verifiedReleases_;projects_.append(p);}
  else {auto unavailable=installed_[id].toObject();unavailable["id"]=id;unavailable["name"]=id;unavailable["channel"]=channel;unavailable["unavailable"]=true;unavailable["error"]=error;projects_.append(unavailable);append(id+" : "+error);}
  refreshReleases(index+1);
 });
}
void Hub::notifyUpdates(){
 for(auto value:projects_){const auto p=value.toObject();const auto id=p["id"].toString(),version=p["version"].toString();const auto record=installed_[id].toObject();
  if(p["unavailable"].toBool()||p["installedOnly"].toBool()||record.isEmpty()||Releases::compare(version,record["version"].toString())<=0||notified_[id+":"+p["channel"].toString()]==version)continue;
  notified_[id+":"+p["channel"].toString()]=version;notify("Mise à jour disponible",p["name"].toString()+" "+version);append(p["name"].toString()+" "+version+" disponible");
 }save();
}

bool Hub::desktopSelfTest(){
 projects_=QJsonArray{QJsonObject{{"id","sdk"},{"name","SDK"},{"unavailable",true},{"error","Aucune release"}},QJsonObject{{"id","tco"},{"name","TCO"},{"unavailable",true}}};
 channels_=QJsonObject{{"sdk","beta"}};render();save();
 auto* sdk=findChild<QComboBox*>("channel-sdk");auto* tco=findChild<QComboBox*>("channel-tco");
 if(!sdk||!tco||sdk->currentData()!="beta"||tco->currentData()!="stable")return false;
 QFile saved(dataDir_+"/settings.json");if(!saved.open(QIODevice::ReadOnly)||QJsonDocument::fromJson(saved.readAll()).object()["channels"].toObject()["sdk"]!="beta")return false;
 showNormal();toggleFullscreen();if(!isFullScreen()){std::puts("Enter fullscreen failed");return false;}toggleFullscreen();if(isFullScreen()){std::puts("Leave fullscreen failed");return false;}
 showMaximized();toggleFullscreen();toggleFullscreen();if(!isMaximized()){std::puts("Restore maximized failed");return false;}
 if(tray_){close();if(isVisible()){std::puts("Close to tray failed");return false;}showHub();if(!isVisible()){std::puts("Show from tray failed");return false;}}
 schedulePushCheck();const auto remaining=pushDebounce_->remainingTime();schedulePushCheck();
 return pushDebounce_->isActive()&&pushDebounce_->remainingTime()<=remaining+1;
}
bool Hub::networkSelfTest()const{
 std::printf("Relay messages: %d; verified official manifests: %d; tray: %d\n",pushMessages_,verifiedReleases_,int(tray_&&tray_->isVisible()));
 return verifiedReleases_>=3;
}
