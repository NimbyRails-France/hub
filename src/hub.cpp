#include "hub.h"
#include "updater.h"
#include <QApplication>
#include <QCloseEvent>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QFileDialog>
#include <QMessageBox>
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QSaveFile>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QCryptographicHash>
#include <QRegularExpression>
#include <QTimer>
#include <QVersionNumber>
#include <QDateTime>
#include <QDesktopServices>
#include <QUuid>
#include <windows.h>
#include <tlhelp32.h>
#include <memory>
namespace {
constexpr auto catalogue="https://raw.githubusercontent.com/NimbyRails-France/hub/main/catalog.json";
QJsonObject readJson(const QString& path){QFile f(path);if(!f.open(QIODevice::ReadOnly)||f.size()>2*1024*1024)return {};return QJsonDocument::fromJson(f.readAll()).object();}
bool writeJson(const QString& path,const QJsonObject& o){QSaveFile f(path);return f.open(QIODevice::WriteOnly)&&f.write(QJsonDocument(o).toJson())>0&&f.commit();}
bool official(const QUrl& url){return url.scheme()=="https"&&url.host()=="github.com"&&url.userInfo().isEmpty()&&url.path().startsWith("/NimbyRails-France/");}
QNetworkRequest request(const QUrl& url){QNetworkRequest r(url);r.setTransferTimeout(30000);r.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::NoLessSafeRedirectPolicy);return r;}
bool running(const wchar_t* name){HANDLE s=CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0);if(s==INVALID_HANDLE_VALUE)return true;PROCESSENTRY32W p{};p.dwSize=sizeof p;bool found=false;if(Process32FirstW(s,&p))do{if(!_wcsicmp(p.szExeFile,name)){found=true;break;}}while(Process32NextW(s,&p));CloseHandle(s);return found;}
}
bool Hub::validProject(const QJsonObject& p){
 static QRegularExpression id("^[a-z][a-z0-9-]{0,63}$"),version("^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}$"),hash("^[a-fA-F0-9]{64}$"),folder("^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$");
 const auto kind=p["kind"].toString();double size=p["size"].toDouble();
 if(p.contains("loaderApi") && (p["loaderApi"].toDouble()!=1 || (kind!="sdk" && kind!="native-mod")))return false;
 if(kind=="native-mod" && p["loaderApi"].toInt()==1){
  static QRegularExpression module("^[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*\\.dll$");
  if(p["module"].toString().size()>=200 || !module.match(p["module"].toString()).hasMatch())return false;
 }
 return id.match(p["id"].toString()).hasMatch()&&version.match(p["version"].toString()).hasMatch()&&hash.match(p["sha256"].toString()).hasMatch()&&
  official(QUrl(p["url"].toString()))&&size>0&&size<=536870912&&size==qint64(size)&&folder.match(p["rootFolder"].toString()).hasMatch()&&
  (kind=="sdk"||kind=="tco"||kind=="native-mod");
}
Hub::Hub(){
 setWindowTitle("NimbyRails France Hub");resize(1120,780);
 dataDir_=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);QDir().mkpath(dataDir_+"/downloads");
 const auto config=readJson(dataDir_+"/settings.json");installed_=config["installed"].toObject();notified_=config["notified"].toObject();
 auto* body=new QWidget;setCentralWidget(body);auto* layout=new QVBoxLayout(body);layout->setContentsMargins(28,24,28,24);layout->setSpacing(16);
 auto* title=new QLabel("NIMBYRAILS FRANCE <span style='color:#75dfc5'>HUB</span>");title->setStyleSheet("font-size:26px;font-weight:700");layout->addWidget(title);
 layout->addWidget(new QLabel("Vos projets, leurs versions et leurs mises à jour, au même endroit."));
 auto* gameRow=new QHBoxLayout;gameRow->addWidget(new QLabel("Dossier du jeu"));game_=new QLineEdit(config["gameDirectory"].toString());gameRow->addWidget(game_);auto* browseGame=new QPushButton("Choisir…");gameRow->addWidget(browseGame);layout->addLayout(gameRow);
 gameStatus_=new QLabel;layout->addWidget(gameStatus_);connect(browseGame,&QPushButton::clicked,this,[this]{chooseGame();});connect(game_,&QLineEdit::editingFinished,this,[this]{detectGame();save();});
 auto* rootRow=new QHBoxLayout;rootRow->addWidget(new QLabel("Bibliothèque de projets"));root_=new QLineEdit(config["root"].toString(dataDir_+"/projects"));rootRow->addWidget(root_);auto* browseRoot=new QPushButton("Choisir…");rootRow->addWidget(browseRoot);layout->addLayout(rootRow);
 connect(browseRoot,&QPushButton::clicked,this,[this]{auto path=QFileDialog::getExistingDirectory(this,"Bibliothèque de projets",root_->text());if(!path.isEmpty()){root_->setText(path);save();}});connect(root_,&QLineEdit::editingFinished,this,[this]{save();});
 auto* options=new QHBoxLayout;automatic_=new QCheckBox("Mettre à jour automatiquement les projets installés");automatic_->setChecked(config["automatic"].toBool(true)&&!qApp->arguments().contains("--ui-test")&&!qApp->arguments().contains("--network-test"));options->addWidget(automatic_);options->addStretch();refresh_=new QPushButton("Actualiser le catalogue");options->addWidget(refresh_);layout->addLayout(options);
 connect(automatic_,&QCheckBox::toggled,this,[this]{save();});connect(refresh_,&QPushButton::clicked,this,[this]{refresh();});
 auto* selfUpdate=new Updater(this);selfUpdate_=selfUpdate;selfUpdate->setEnabled(automatic_->isChecked());
 auto* updateRow=new QHBoxLayout;auto* updateLabel=new QLabel;auto* updateButton=new QPushButton("Vérifier le Hub");updateRow->addWidget(updateLabel,1);updateRow->addWidget(updateButton);layout->addLayout(updateRow);
 connect(selfUpdate,&Updater::changed,this,[selfUpdate,updateLabel,updateButton]{updateLabel->setText(selfUpdate->status());updateButton->setText(selfUpdate->ready()?"Redémarrer le Hub pour appliquer":"Vérifier le Hub");});
 connect(updateButton,&QPushButton::clicked,this,[this,selfUpdate]{if(busy_){append("Veuillez attendre la fin des opérations.");return;}if(selfUpdate->ready())selfUpdate->restart();else selfUpdate->check();});connect(automatic_,&QCheckBox::toggled,this,[selfUpdate](bool value){selfUpdate->setEnabled(value);});
 table_=new QTableWidget(0,5);table_->setHorizontalHeaderLabels({"Projet","Disponible","Installé","Compatibilité","Dossier"});table_->horizontalHeader()->setSectionResizeMode(QHeaderView::ResizeToContents);table_->horizontalHeader()->setSectionResizeMode(4,QHeaderView::Stretch);table_->setSelectionBehavior(QAbstractItemView::SelectRows);table_->setSelectionMode(QAbstractItemView::SingleSelection);table_->setEditTriggers(QAbstractItemView::NoEditTriggers);layout->addWidget(table_,1);
 auto* actions=new QHBoxLayout;install_=new QPushButton("Installer / Mettre à jour");launch_=new QPushButton("Ouvrir");rollback_=new QPushButton("Revenir à la version précédente");remove_=new QPushButton("Désinstaller");for(auto* b:{install_,launch_,rollback_,remove_})actions->addWidget(b);layout->addLayout(actions);
 connect(install_,&QPushButton::clicked,this,[this]{installProject(table_->currentRow());});connect(launch_,&QPushButton::clicked,this,[this]{action("open");});connect(rollback_,&QPushButton::clicked,this,[this]{action("rollback");});connect(remove_,&QPushButton::clicked,this,[this]{action("remove");});
 status_=new QLabel("Chargement du catalogue…");layout->addWidget(status_);log_=new QPlainTextEdit;log_->setReadOnly(true);log_->setMaximumBlockCount(500);log_->setMaximumHeight(145);layout->addWidget(log_);
 setStyleSheet("QWidget{background:#101b24;color:#dce8ed;font-family:'Segoe UI';font-size:13px} QLineEdit,QTableWidget,QPlainTextEdit{background:#172733;border:1px solid #355160;border-radius:5px;padding:6px} QPushButton{background:#254553;border:1px solid #427082;padding:9px;border-radius:5px} QPushButton:hover{background:#326474} QPushButton:disabled{color:#75818a} QHeaderView::section{background:#213b49;padding:9px;border:0} QTableWidget::item:selected{background:#285653} QCheckBox{spacing:8px}");
 if(game_->text().isEmpty()){QString candidate="C:/Program Files (x86)/Steam/steamapps/common/NIMBY Rails";if(QFile::exists(candidate+"/NIMBYRails.exe"))game_->setText(candidate);}
 setupDesktop();restoreGeometry(QByteArray::fromBase64(config["geometry"].toString().toLatin1()));detectGame();QTimer::singleShot(0,this,[this]{refresh();});auto* timer=new QTimer(this);connect(timer,&QTimer::timeout,this,[this]{refresh();});timer->start(15*60*1000);
}
void Hub::append(const QString& s){log_->appendPlainText(QTime::currentTime().toString("HH:mm:ss")+"  "+s);status_->setText(s);}
void Hub::closeEvent(QCloseEvent* event){
 if(tray_&&tray_->isVisible()){save();hide();event->ignore();return;}
 if(busy_){append("Veuillez attendre la fin des opérations avant de quitter.");event->ignore();return;}
 save();QMainWindow::closeEvent(event);
}void Hub::setBusy(bool busy){busy_=busy;for(auto* b:{install_,remove_,rollback_,refresh_})b->setEnabled(!busy);game_->setEnabled(!busy);root_->setEnabled(!busy);}
void Hub::save(){if(!writeJson(dataDir_+"/settings.json",{{"gameDirectory",game_->text()},{"root",root_->text()},{"automatic",automatic_->isChecked()},{"installed",installed_},{"notified",notified_},{"geometry",QString::fromLatin1(saveGeometry().toBase64())}}))append("Impossible d'enregistrer les réglages");}
void Hub::chooseGame(){auto p=QFileDialog::getExistingDirectory(this,"Dossier contenant NIMBYRails.exe",game_->text());if(!p.isEmpty()){game_->setText(p);detectGame();save();render();}}
void Hub::detectGame(){QFile f(game_->text()+"/NIMBYRails.exe");gameHash_.clear();if(f.open(QIODevice::ReadOnly)){QCryptographicHash h(QCryptographicHash::Sha256);if(h.addData(&f))gameHash_=QString::fromLatin1(h.result().toHex());}gameStatus_->setText(gameHash_.isEmpty()?"Jeu introuvable : choisissez son dossier.":"Version du jeu identifiée · SHA-256 "+gameHash_.left(16)+"…");}
bool Hub::compatible(const QJsonObject& p,QString& reason)const{
 const auto hashes=p["gameSha256"].toArray();bool known=false;for(auto h:hashes)if(h.toString().compare(gameHash_,Qt::CaseInsensitive)==0&&!gameHash_.isEmpty())known=true;
 if(!known){reason="Version du jeu non prise en charge";return false;}
 if(p["kind"]=="sdk"){
  const auto candidate=QVersionNumber::fromString(p["version"].toString());
  for(const auto& v:installed_){const auto dependent=v.toObject();if(!dependent.contains("sdkMin"))continue;
   if(candidate<QVersionNumber::fromString(dependent["sdkMin"].toString())||candidate>=QVersionNumber::fromString(dependent["sdkMaxExclusive"].toString())){reason="Version SDK incompatible avec "+dependent["id"].toString();return false;}}
 }
 if(p["kind"]!="sdk" && p["loaderApi"].toInt()>installed_["sdk"].toObject()["loaderApi"].toInt()){reason="Mise à jour du NRF Loader requise pour les mods C++";return false;}
 if(p["kind"]=="sdk"){for(const auto& v:installed_){const auto dependent=v.toObject();if(dependent["kind"]!="sdk" && dependent["loaderApi"].toInt()>p["loaderApi"].toInt()){reason="NRF Loader requis par "+dependent["id"].toString();return false;}}}
 if(p.contains("sdkMin")){const auto sdk=installed_["sdk"].toObject();const auto v=QVersionNumber::fromString(sdk["version"].toString());if(v<QVersionNumber::fromString(p["sdkMin"].toString())||v>=QVersionNumber::fromString(p["sdkMaxExclusive"].toString())){reason="SDK "+p["sdkMin"].toString()+" requis";return false;}}
 reason="Compatible";return true;
}
void Hub::render(){
 for(int i=projects_.size()-1;i>=0;--i){const auto p=projects_[i].toObject();if(p["installedOnly"].toBool()&&!installed_.contains(p["id"].toString()))projects_.removeAt(i);}
 for(auto it=installed_.begin();it!=installed_.end();++it){
  bool listed=false;for(const auto value:projects_)if(value.toObject()["id"].toString()==it.key()){listed=true;break;}
  if(!listed){auto p=it.value().toObject();p["name"]=p["name"].toString(it.key());p["installedOnly"]=true;projects_.append(p);}
 }
 table_->setRowCount(projects_.size());for(int i=0;i<projects_.size();++i){auto p=projects_[i].toObject(),record=installed_[p["id"].toString()].toObject();QString reason;compatible(p,reason);QStringList values{p["name"].toString(),p["installedOnly"].toBool()?"Local (non publié)":p["version"].toString(),record["version"].toString("—"),reason,record["directory"].toString()};for(int c=0;c<values.size();++c)table_->setItem(i,c,new QTableWidgetItem(values[c]));}
}
void Hub::refresh(){if(busy_)return;setBusy(true);auto* reply=network_.get(request(QUrl(catalogue)));auto bytes=std::make_shared<QByteArray>();connect(reply,&QIODevice::readyRead,this,[reply,bytes]{*bytes+=reply->readAll();if(bytes->size()>1024*1024)reply->abort();});connect(reply,&QNetworkReply::finished,this,[this,reply,bytes]{*bytes+=reply->readAll();const bool ok=reply->error()==QNetworkReply::NoError&&bytes->size()<1024*1024;reply->deleteLater();setBusy(false);if(!ok){render();append("Catalogue indisponible · vos installations sont conservées");refreshReleases(0);return;}auto doc=QJsonDocument::fromJson(*bytes).object();if(doc["schema"].toInt()!=1){append("Catalogue incompatible");return;}QJsonArray valid;QSet<QString> ids;for(auto v:doc["projects"].toArray()){auto p=v.toObject();if(!validProject(p)||ids.contains(p["id"].toString())){append("Catalogue rejeté : projet invalide");return;}ids.insert(p["id"].toString());valid.append(p);}for(int i=0;i<valid.size();++i){for(auto old:projects_){if(old.toObject()["id"]==valid[i].toObject()["id"]&&QVersionNumber::fromString(old.toObject()["version"].toString())>QVersionNumber::fromString(valid[i].toObject()["version"].toString()))valid[i]=old;}}projects_=valid;detectGame();render();append("Catalogue à jour · "+QString::number(projects_.size())+" projets publiés");refreshReleases(0);});}
void Hub::checkUpdates(){if(busy_||!automatic_->isChecked())return;for(int i=0;i<projects_.size();++i){auto p=projects_[i].toObject(),record=installed_[p["id"].toString()].toObject();if(!record.isEmpty()&&QVersionNumber::fromString(p["version"].toString())>QVersionNumber::fromString(record["version"].toString())){QString reason;if(compatible(p,reason)){installProject(i,true);if(busy_)return;}else append(p["name"].toString()+" : mise à jour différée · "+reason);}}}
void Hub::installProject(int row,bool automatic){if(busy_||row<0||row>=projects_.size())return;auto p=projects_[row].toObject();if(p["installedOnly"].toBool()){append("Ce projet local doit être mis à jour avec son paquet local.");return;}QString reason;detectGame();if(!compatible(p,reason)){append(reason);return;}if(running(L"NIMBYRails.exe")||running(L"NimbyTco.exe")){append("Installation différée : fermez NIMBY Rails et le TCO.");return;}auto record=installed_[p["id"].toString()].toObject();QString destination=record["directory"].toString();if(destination.isEmpty()){if(automatic)return;auto parent=QFileDialog::getExistingDirectory(this,"Choisir le dossier parent du projet",root_->text());if(parent.isEmpty())return;destination=QDir(parent).absoluteFilePath(p["id"].toString());}if(!automatic&&QMessageBox::question(this,"Installation",p["name"].toString()+" sera installé dans :\n"+destination+"\n\nLe SDK installe aussi son chargeur dans le jeu. Continuer ?")!=QMessageBox::Yes)return;download(p,destination);}
void Hub::download(const QJsonObject& p,const QString& destination){setBusy(true);append("Téléchargement : "+p["name"].toString());auto path=dataDir_+"/downloads/"+QUuid::createUuid().toString(QUuid::WithoutBraces)+".zip";auto file=std::make_shared<QSaveFile>(path);if(!file->open(QIODevice::WriteOnly)){setBusy(false);append("Écriture du téléchargement impossible");return;}auto hash=std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);auto size=std::make_shared<qint64>(0);auto* reply=network_.get(request(QUrl(p["url"].toString())));connect(reply,&QIODevice::readyRead,this,[reply,file,hash,size,p]{auto data=reply->readAll();*size+=data.size();if(*size>qint64(p["size"].toDouble())||file->write(data)!=data.size()){reply->abort();return;}hash->addData(data);});connect(reply,&QNetworkReply::finished,this,[this,reply,file,hash,size,p,path,destination]{bool ok=reply->error()==QNetworkReply::NoError&&*size==qint64(p["size"].toDouble())&&hash->result().toHex()==p["sha256"].toString().toLatin1().toLower();reply->deleteLater();if(!ok||!file->commit()){file->cancelWriting();setBusy(false);append("Téléchargement rejeté : intégrité ou réseau");return;}runOperation({{"action","install"},{"project",p},{"archive",path},{"destination",destination},{"gameDirectory",game_->text()},{"expectedGameHash",gameHash_}});});}
void Hub::runOperation(QJsonObject req){setBusy(true);req["resultFile"]=dataDir_+"/operation-result.json";const auto requestPath=dataDir_+"/operation-request.json";if(!writeJson(requestPath,req)){setBusy(false);append("Écriture de la demande impossible");return;}operation_=new QProcess(this);connect(operation_,&QProcess::readyReadStandardOutput,this,[this]{log_->appendPlainText(QString::fromUtf8(operation_->readAllStandardOutput()));});connect(operation_,&QProcess::readyReadStandardError,this,[this]{log_->appendPlainText(QString::fromLocal8Bit(operation_->readAllStandardError()));});connect(operation_,&QProcess::errorOccurred,this,[this](QProcess::ProcessError e){if(e==QProcess::FailedToStart){setBusy(false);append("Impossible de démarrer l'installateur");operation_->deleteLater();operation_=nullptr;}});connect(operation_,&QProcess::finished,this,[this,req](int code,QProcess::ExitStatus state){auto* completed=operation_;operation_=nullptr;completed->deleteLater();setBusy(false);if(code||state!=QProcess::NormalExit){append("Opération échouée · consultez le journal");notify("Opération échouée","Ouvrez le Hub pour consulter le journal.");return;}auto result=readJson(dataDir_+"/operation-result.json");const auto id=req["project"].toObject()["id"].toString();if(req["action"]=="remove")installed_.remove(id);else installed_[id]=result;save();render();append("Opération terminée");notify("Opération terminée",id+" : opération terminée.");QTimer::singleShot(500,this,[this]{checkUpdates();});});operation_->start("powershell.exe",{"-NoProfile","-ExecutionPolicy","Bypass","-File",QCoreApplication::applicationDirPath()+"/scripts/manage.ps1","-RequestFile",requestPath});}
void Hub::action(const QString& name){int row=table_->currentRow();if(busy_||row<0)return;auto p=projects_[row].toObject(),record=installed_[p["id"].toString()].toObject();if(record.isEmpty())return;const auto dir=record["directory"].toString();if(name=="open"){if(p["kind"]=="tco")QProcess::startDetached(dir+"/NimbyTco.exe",{});else QDesktopServices::openUrl(QUrl::fromLocalFile(dir));return;}if(running(L"NIMBYRails.exe")||running(L"NimbyTco.exe")){append("Fermez le jeu et le TCO avant cette opération");return;}if(QMessageBox::question(this,"Confirmer",name=="remove"?"Désinstaller ce projet ?":"Restaurer la version précédente et suspendre les mises à jour automatiques ?")!=QMessageBox::Yes)return;if(name=="rollback")automatic_->setChecked(false);runOperation({{"action",name},{"project",p},{"destination",dir},{"gameDirectory",game_->text()},{"expectedGameHash",gameHash_}});}
