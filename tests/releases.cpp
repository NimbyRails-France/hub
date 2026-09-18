#include "releases.h"
#include <QCoreApplication>
#include <QJsonDocument>
#include <cstdio>
#include <cstdlib>

void require(bool ok,const char* message){if(!ok){std::fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
QJsonObject release(QString version,bool prerelease=false){
 const auto base="https://github.com/NimbyRails-France/sdk/releases/download/v"+version+"/";
 return {{"tag_name","v"+version},{"published_at","2026-09-18T10:00:00Z"},{"prerelease",prerelease},{"draft",false},
  {"assets",QJsonArray{QJsonObject{{"name","project.json"},{"state","uploaded"},{"browser_download_url",base+"project.json"}},
   QJsonObject{{"name","sdk.zip"},{"state","uploaded"},{"browser_download_url",base+"sdk.zip"},{"size",1234},{"digest","sha256:"+QString(64,'a')}}}}};
}
int main(int argc,char** argv){
 QCoreApplication app(argc,argv);
 require(Releases::compare("1.0.0-beta.10","1.0.0-beta.2")>0,"numeric prerelease sorting");
 require(Releases::compare("1.0.0-beta.1","1.0.0-alpha.99")>0,"beta follows alpha");
 require(Releases::compare("1.0.0","1.0.0-beta.99")>0,"stable follows prerelease");
 require(Releases::compare("1.1.0-alpha.1","1.0.9")>0,"base version before channel rank");
 require(!Releases::validVersion("01.0.0")&&!Releases::validVersion("1.0.0-beta.01")&&!Releases::validVersion("1.0.0-rc.1"),"reject ambiguous or unsupported versions");
 QJsonArray list{release("1.0.0"),release("2.0.0-beta.2",true),release("2.0.0-alpha.1",true),release("2.0.0-beta.10",true)};
 require(Releases::select(list,"stable","project.json")["tag_name"]=="v1.0.0","stable never receives prereleases");
 require(Releases::select(list,"beta","project.json")["tag_name"]=="v2.0.0-beta.10","select highest beta rather than API order");
 require(Releases::select(list,"alpha","project.json")["tag_name"]=="v2.0.0-alpha.1","alpha selection independent of beta");
 require(Releases::select({release("1.0.0")},"beta","project.json").isEmpty(),"no fallback to another channel");
 auto draft=release("9.0.0");draft["draft"]=true;list.append(draft);
 auto mismatch=release("8.0.0",true);list.append(mismatch);
 require(Releases::select(list,"stable","project.json")["tag_name"]=="v1.0.0","reject draft and inconsistent prerelease flag");
 auto incomplete=release("7.0.0");incomplete["assets"]=QJsonArray{};list.append(incomplete);
 require(Releases::select(list,"stable","project.json")["tag_name"]=="v1.0.0","ignore releases without install metadata");
 QJsonObject channels{{"sdk","beta"},{"hub","alpha"}};channels=QJsonDocument::fromJson(QJsonDocument(channels).toJson()).object();
 require(Releases::selectedChannel(channels,"sdk")=="beta"&&Releases::selectedChannel(channels,"hub")=="alpha"&&Releases::selectedChannel(channels,"tco")=="stable","independent saved preferences and stable default");
 channels["sdk"]="garbage";require(Releases::selectedChannel(channels,"sdk")=="stable","invalid preference defaults to stable");
 const auto stable=release("1.0.0");
 QJsonObject metadata{{"version","1.0.0"},{"url","https://github.com/NimbyRails-France/sdk/releases/download/v1.0.0/sdk.zip"},{"size",1234},{"sha256",QString(64,'a')}};
 require(Releases::matchesAsset(metadata,stable,"sdk"),"existing manifest format remains compatible");
 metadata["size"]=1235;require(!Releases::matchesAsset(metadata,stable,"sdk"),"mismatched asset size rejected");metadata["size"]=1234;
 metadata["sha256"]=QString(64,'b');require(!Releases::matchesAsset(metadata,stable,"sdk"),"mismatched GitHub digest rejected");metadata["sha256"]=QString(64,'a');
 metadata["version"]="1.0.1";require(!Releases::matchesAsset(metadata,stable,"sdk"),"cross-version manifest rejected");metadata["version"]="1.0.0";
 metadata["channel"]="beta";require(!Releases::matchesAsset(metadata,stable,"sdk"),"cross-channel manifest rejected");metadata.remove("channel");
 require(!Releases::matchesAsset(metadata,stable,"tco"),"cross-repository asset rejected");
 auto bad=stable;auto assets=stable["assets"].toArray();auto manifest=assets[0].toObject();manifest["browser_download_url"]="https://evil.example/project.json";assets[0]=manifest;bad["assets"]=assets;
 require(Releases::assetUrl(bad,"sdk","project.json").isEmpty(),"untrusted manifest endpoint rejected");
 require(!Releases::select(list,"dev","project.json").size(),"desktop project has no dev channel");
 std::puts("Release channels: 21 checks passed");return 0;
}
