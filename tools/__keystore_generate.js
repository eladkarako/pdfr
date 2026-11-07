/* minimal program to handle 'keystore.properties' and run keytool to generate a keystore.
 */
"use strict";
const fs              = require("fs")
     ,content         = fs.readFileSync("./keystore.properties", {encoding:"utf-8", signal:AbortSignal.timeout(3*1000)})
     ,properties      = {}
     ;


Array.from( content.matchAll(/^([^\=]+)\s*=\s*([^\r\n]+)/gms) )
     .forEach(match=>{
        properties[ match[1].trim() ] = match[2].trim();
     });


console.log(
  JSON.stringify(properties, null, 2)
      .replace(/\r+/gms, "")
      .replace(/,\n /gms, "\n ,")
      .replace(/ *(,( +))/gms, "$2,")
      .replace(/\n/gms, "\r\n")
);


let is_exists_already = false;
try{
  fs.accessSync( properties.storeFile );
  is_exists_already = true;
}catch(err){}

process.env["PDFR_STORE_PASSWORD"] = properties.storePassword;
process.env["PDFR_KEY_PASSWORD"]   = properties.keyPassword;
if(!process.env["JAVA_HOME"]){
  process.env["JAVA_HOME"] = "D:\\software\\java\\jdk-17.0.1";
  process.env["PATH"] = process.env["JAVA_HOME"] + ";" + process.env["PATH"];
}

const {spawnSync}     = require("child_process");

if(false === is_exists_already){
  console.log("starting keytool..");
  const result          = spawnSync("keytool.exe", [
    "-genkeypair"
   ,"-v"
   ,"-keystore"      , properties.storeFile
   ,"-alias"         , properties.keyAlias
   ,"-storepass:env" , "PDFR_STORE_PASSWORD"
   ,"-keypass:env"   , "PDFR_KEY_PASSWORD"
   ,"-keyalg"        , "RSA"
   ,"-storetype"     , "PKCS12"
   ,"-keysize"       , "2048"
   ,"-sigalg"        , "SHA256withRSA"
   ,"-validity"      , "10000"
   ,"-dname"         , "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, S=Unknown, C=US"
  ],{encoding:"utf-8", signal:AbortSignal.timeout(10*1000), stdio:["ignore","pipe","pipe"], shell:false});

  console.log(result.output.join(""));
  console.log("keytool finished with exit-code: " + result.status);

  try{
    fs.accessSync( properties.storeFile );
    is_exists_already = true;
  }catch(err){}

  if(is_exists_already){
    console.log("SUCCESS: " + properties.storeFile + " generated successfully.");

    console.log("verifying newly created keystore file against keystore password and alias...");
    const verifing_result = spawnSync("keytool.exe", [
      "-list"
     ,"-v"
     ,"-keystore"      , properties.storeFile
     ,"-alias"         , properties.keyAlias
     ,"-storepass:env" , "PDFR_STORE_PASSWORD"
    ],{encoding:"utf-8", signal:AbortSignal.timeout(10*1000), stdio:["ignore","pipe","pipe"], shell:false});
    console.log(verifing_result.output.join(""));
    console.log("verifying finished with exit-code: " + verifing_result.status);
  }
  else{
    console.log("ERROR: " + properties.storeFile + " not written to file.");
  }

}else{
  console.log("verifying EXISTING keystore file against known keystore password and alias...");
  const verifing_result = spawnSync("keytool.exe", [
    "-list"
   ,"-v"
   ,"-keystore"      , properties.storeFile
   ,"-alias"         , properties.keyAlias
   ,"-storepass:env" , "PDFR_STORE_PASSWORD"
  ],{encoding:"utf-8", signal:AbortSignal.timeout(10*1000), stdio:["ignore","pipe","pipe"], shell:false});
  console.log(verifing_result.output.join(""));
  console.log("verifying finished with exit-code: " + verifing_result.status);
}

