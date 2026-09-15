#pragma once
#include <QMainWindow>
#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QTableWidget>
#include <QLineEdit>
#include <QLabel>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QProcess>
#include <QCheckBox>
#include <QSystemTrayIcon>
#include <QTimer>
#include <QElapsedTimer>
class Updater;
class Hub:public QMainWindow {
public:
 Hub();
 static bool validProject(const QJsonObject&);
 void refresh();
 bool desktopSelfTest();
 bool networkSelfTest()const;
protected:
 void closeEvent(QCloseEvent*)override;
private:
 QNetworkAccessManager network_;
 QJsonArray projects_;QJsonObject installed_;
 QTableWidget* table_;QLineEdit *game_,*root_;QLabel *gameStatus_,*status_;QPlainTextEdit* log_;QCheckBox* automatic_;
 QPushButton *install_,*launch_,*remove_,*rollback_,*refresh_;
 QString dataDir_,gameHash_;bool busy_=false;QProcess* operation_=nullptr;
 QSystemTrayIcon* tray_=nullptr;Updater* selfUpdate_=nullptr;
 QTimer* pushDebounce_=nullptr;QElapsedTimer pushThrottle_;
 QJsonObject notified_;int reconnectMs_=2000;bool selfNotified_=false;
 int pushMessages_=0,verifiedReleases_=0;
 QLabel* pushStatus_=nullptr;Qt::WindowStates beforeFullscreen_{};
 void setupDesktop();void showHub();void toggleFullscreen();void quitHub();
 void notify(const QString& title,const QString& message);
 void connectPush();void schedulePushCheck();void refreshReleases(int index);
 void notifyUpdates();
 void save();void render();void detectGame();void chooseGame();void checkUpdates();
 bool compatible(const QJsonObject&,QString& reason)const;
 void installProject(int row,bool automatic=false);
 void download(const QJsonObject&,const QString& destination);
 void runOperation(QJsonObject request);
 void action(const QString& name);
 void append(const QString& text);void setBusy(bool busy);

};
