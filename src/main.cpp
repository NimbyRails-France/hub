#include "hub.h"
#include <QApplication>
#include <QTimer>
#include <QLockFile>
#include <QDir>
#include <QStandardPaths>
#include <QLocalServer>
#include <QLocalSocket>
#include <cstdio>
int main(int argc,char** argv){
 QApplication app(argc,argv);app.setOrganizationName("NimbyRailsFrance");app.setApplicationName("NRFHub");app.setApplicationVersion(HUB_VERSION);
 if(app.arguments().contains("--self-test")){
  QJsonObject p{{"id","tco"},{"kind","tco"},{"version","0.4.0"},{"url","https://github.com/NimbyRails-France/tco/releases/download/v0.4.0/tco.zip"},{"sha256",QString(64,'a')},{"size",1234},{"rootFolder","NimbyTco-0.4.0"}};
  if(!Hub::validProject(p))return 1;p["id"]="../bad";if(Hub::validProject(p))return 2;p["id"]="tco";p["url"]="http://example.com/file";if(Hub::validProject(p))return 3;
  std::puts("Hub catalogue validation passed");return 0;
 }
 const bool uiTest=app.arguments().contains("--ui-test");
 const bool networkTest=app.arguments().contains("--network-test");
 if(uiTest||networkTest)app.setApplicationName("NRFHub-DesktopTest");
 const auto data=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);QDir().mkpath(data);
 const auto serverName=QString("NRFHub-")+QString::number(qHash(data));
 QLockFile lock(data+"/hub.lock");if(!lock.tryLock(0)){
  QLocalSocket socket;socket.connectToServer(serverName);if(socket.waitForConnected(1500)){socket.write("show");socket.waitForBytesWritten(1000);return 0;}return 2;
 }
 QLocalServer server;server.setSocketOptions(QLocalServer::UserAccessOption);QLocalServer::removeServer(serverName);server.listen(serverName);
 Hub hub;hub.show();
 QObject::connect(&server,&QLocalServer::newConnection,&hub,[&]{while(auto* socket=server.nextPendingConnection()){socket->disconnectFromServer();socket->deleteLater();}if(hub.isMinimized())hub.setWindowState(hub.windowState()&~Qt::WindowMinimized);hub.show();hub.raise();hub.activateWindow();});
 if(uiTest)QTimer::singleShot(100,&app,[&]{const bool ok=hub.desktopSelfTest();std::puts(ok?"Tray, fullscreen, restore and debounce passed":"Desktop tests failed");app.exit(ok?0:4);});
 if(networkTest)QTimer::singleShot(25000,&app,[&]{app.exit(hub.networkSelfTest()?0:5);});
 const auto args=app.arguments();int at=args.indexOf("--screenshot");
 if(at>=0&&at+1<args.size())QTimer::singleShot(7000,&app,[&]{hub.grab().save(args[at+1]);app.quit();});
 return app.exec();
}
