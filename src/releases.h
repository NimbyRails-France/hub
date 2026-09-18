#pragma once
#include <QObject>
#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QHash>
#include <functional>

namespace Releases {
QString channel(const QString& version);
bool validVersion(const QString& version);
int compare(const QString& left,const QString& right);
QString selectedChannel(const QJsonObject& settings,const QString& project);
QJsonObject select(const QJsonArray& releases,const QString& channel,const QString& manifest);
QString assetUrl(const QJsonObject& release,const QString& repository,const QString& name);
bool matchesAsset(const QJsonObject& manifest,const QJsonObject& release,const QString& repository);
}

// Public GitHub endpoints only; no account token is stored in the desktop app.
class ReleaseClient:public QObject {
public:
 using Result=std::function<void(QJsonObject,QString)>;
 explicit ReleaseClient(QObject* parent=nullptr);
 void discover(std::function<void(QStringList,QString)> done);
 void fetch(const QString& repository,const QString& channel,const QString& manifest,Result done);
private:
 struct Cached {QByteArray etag,body;};
 QNetworkAccessManager network_;
 QHash<QString,Cached> cache_;
 QStringList repositories_;
 qint64 discoverUntil_=0,retryAfter_=0;
 void get(const QUrl& url,int limit,std::function<void(QByteArray,QString)> done);
 void pages(const QString& path,int page,QJsonArray rows,std::function<void(QJsonArray,QString)> done);
};
