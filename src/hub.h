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
class Hub:public QMainWindow {
public:
 Hub();
 static bool validProject(const QJsonObject&);
 void refresh();
protected:
 void closeEvent(QCloseEvent*)override;
private:
 QNetworkAccessManager network_;
 QJsonArray projects_;QJsonObject installed_;
 QTableWidget* table_;QLineEdit *game_,*root_;QLabel *gameStatus_,*status_;QPlainTextEdit* log_;QCheckBox* automatic_;
 QPushButton *install_,*launch_,*remove_,*rollback_,*refresh_;
 QString dataDir_,gameHash_;bool busy_=false;QProcess* operation_=nullptr;
 void save();void render();void detectGame();void chooseGame();void checkUpdates();
 bool compatible(const QJsonObject&,QString& reason)const;
 void installProject(int row,bool automatic=false);
 void download(const QJsonObject&,const QString& destination);
 void runOperation(QJsonObject request);
 void action(const QString& name);
 void append(const QString& text);void setBusy(bool busy);
 void updateSelf(const QJsonObject& release);
};
