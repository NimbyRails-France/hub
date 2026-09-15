#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QUrl>
#include <QJsonObject>
struct UpdateRelease {QString version;QUrl url;QByteArray hash;qint64 size=0;};
bool parseRelease(const QJsonObject&,const QString& current,UpdateRelease&);
class Updater:public QObject {
 Q_OBJECT
 Q_PROPERTY(QString status READ status NOTIFY changed)
 Q_PROPERTY(bool ready READ ready NOTIFY changed)
public:
 explicit Updater(QObject* parent=nullptr);
 QString status()const{return status_;}
 bool ready()const{return !installer_.isEmpty();}
 Q_INVOKABLE void check();
 Q_INVOKABLE void restart();
 void setEnabled(bool value){enabled_=value;}
signals:void changed();
private:
 QNetworkAccessManager network_;QUrl feed_;QString status_,installer_;QByteArray expectedHash_;
 bool busy_=false,relaunch_=false,enabled_=true;
 void message(const QString& text){status_=text;emit changed();}
 void download(const UpdateRelease& release);
 void installOnExit();
};
