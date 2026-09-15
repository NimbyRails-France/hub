#include "hub.h"
#include <QApplication>
#include <QTimer>
#include <QLockFile>
#include <QDir>
#include <QStandardPaths>
#include <cstdio>
int main(int argc,char** argv){
 QApplication app(argc,argv);app.setOrganizationName("NimbyRailsFrance");app.setApplicationName("NRFHub");app.setApplicationVersion(HUB_VERSION);
 if(app.arguments().contains("--self-test")){
  QJsonObject p{{"id","tco"},{"kind","tco"},{"version","0.4.0"},{"url","https://github.com/NimbyRails-France/tco/releases/download/v0.4.0/tco.zip"},{"sha256",QString(64,'a')},{"size",1234},{"rootFolder","NimbyTco-0.4.0"}};
  if(!Hub::validProject(p))return 1;p["id"]="../bad";if(Hub::validProject(p))return 2;p["id"]="tco";p["url"]="http://example.com/file";if(Hub::validProject(p))return 3;
  std::puts("Hub catalogue validation passed");return 0;
 }
 const auto data=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);QDir().mkpath(data);
 QLockFile lock(data+"/hub.lock");if(!lock.tryLock(0))return 2;
 Hub hub;hub.show();
 const auto args=app.arguments();int at=args.indexOf("--screenshot");
 if(at>=0&&at+1<args.size())QTimer::singleShot(7000,&app,[&]{hub.grab().save(args[at+1]);app.quit();});
 return app.exec();
}
