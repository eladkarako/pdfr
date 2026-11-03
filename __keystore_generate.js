/* - reads, parse, validate './keystore.properties' file,
 * - use its values to run keytool command, to generate a keystore file, if needed, 
 * - passwords are pre-stored in environment variable, and keytool uses the environment-variable name instead of plain-text passwords in the command-line, 
 * - no overwrite existing keystore file, and basically this is almost unusable script that would be run manually once per lifetime of the application.
 */
"use strict";
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const beautifier = (o)=>{ //object beautifier, json beautifier, beautify
  return JSON.stringify(o, null, 2)
             .replace(/\r+/gms,"")
             .replace(/,\n /gms, "\n ,")
             .replace(/ *(,( +))/gms,"$2,")
             .replace(/\n/gms,"\r\n")
             ;
};

const get_is_exists = async (file_path)=>{
  const R_OK = 4; //fs.constants.R_OK
  const W_OK = 2; //fs.constants.W_OK
  let is_exists = false;
  try {
    await fs.access(filePath, R_OK | W_OK);
    is_exists = true;
  }catch(err){}
};

//generic runner
const run = (execute, args, env)=>{
  const TIMEOUT_MS = 10 * 1000;
  const options = {env, stdio:["ignore", "pipe", "pipe"], shell:false, signal:AbortSignal.timeout(TIMEOUT_MS), timeout:TIMEOUT_MS}
  return new Promise((resolve,reject)=>{
    const sub_process = spawn(execute, args, options);

    sub_process.stdout.pipe(process.stdout);
    sub_process.stderr.pipe(process.stdout);

    sub_process.once("error", reject);

    sub_process.once("close", (code, signal)=>{
      const signal_exit_code = signal ? (os.constants.signals[signal] ? os.constants.signals[signal] + 128 : 1) : 1;   //only matters if no `code`.
      const exit_code        = Number.isInteger(code) ? code : signal_exit_code;
      const message          = signal ? `keytool terminated by signal ${signal}. exit-code:${exit_code}.` : `keytool exited with status ${code}. exit-code:${exit_code}.`;
      if(0 === code){
        resolve(exit_code);
      }else{
        reject(new Error(message));
      }
    });
  });
};

const keystore_properties_file_path = path.resolve(__dirname, "./keystore.properties");
const known_keys_in_keystore_properties = ["keystore_file", "keystore_file_password", "keyAlias", "keyPassword"]

const get_properties = async ()=>{ //extract entries from `keystore.properties` file and validate some aspects of them.
  const content = await fs.readFile(keystore_properties_file_path, {"encoding":"utf8", signal:AbortSignal.timeout(3 * 1000)});
  const keystore_properties = Array.from(content.matchAll(/^([^\=]+)=([^\r\n]+)/imgs))
                                   .reduce((carry,current)=>{
                                      const key   = current[1].trim();
                                      const value = current[2].trim();
                                      carry[key]  = value;
                                      return carry;
                                   },{});              //{storeFile: 'keystore/release.jks', storePassword: 'your_keystore_password', keyAlias: 'your_key_alias', keyPassword: 'your_key_password'}

  console.log(`[INFO] parsing \"${keystore_properties_file_path}\" done.`);
  console.log( beautifier(keystore_properties) );

  const keys_in_keystore_properties = Object.keys(keystore_properties);
  
  known_keys_in_keystore_properties.forEach(key=>{
    if(-1 === keys_in_keystore_properties.indexOf(key)){
      throw new Error(`ERROR: \"${key}\" not found in \"${keystore_properties_file_path}\" .`);
    }
  });

  keys_in_keystore_properties.forEach(key=>{
    const value = keystore_properties[key];
    if("" === value){
      throw new Error(`ERROR: \"${key}\" is empty.`);
    }
  });

  if(keystore_properties.storePassword !== keystore_properties.keyPassword){
      throw new Error(`ERROR: PKCS12 needs identical values for storePassword and keyPassword. they are different.`);
  }

  return keystore_properties;
};

const keytool_path = (process.env["JAVA_HOME"] || process.env["JRE_HOME"] || "D:/software/java/jdk-17.0.1").replace(/\\+/gm,"/").replace(/\/+$/gm,"") + "/bin/keytool.exe";

(async ()=>{
  const keystore_properties = await get_properties();
  console.log(properties);

  properties.storeFile = path.resolve(__dirname, properties.storeFile).replace(/\\+/gm,"/"); //normalize path

  if(get_is_exists(properties.storeFile)){
    throw new Error(`ERROR: \"${properties.storeFile}\" - already exists (will not overwrite it).`);
  }

  const directory_path = path.dirname(properties.storeFile);
  try{
    await fs.mkdir(directory_path,{recursive:true});
  }catch(err){}

  const env = process.env;  //will be used to pass the passwords to keytool without using plain-text command.
  env["PDFR_KEYSTORE_PASSWORD"] = properties.storePassword;
  env["PDFR_KEY_PASSWORD"]      = properties.keyPassword;

  const args = ["-genkeypair"
               ,"-keystore"      , properties.storeFile
               ,"-alias"         , properties.keyAlias
               ,"-storepass:env" , "PDFR_KEYSTORE_PASSWORD" //:env for passwords tells keytool to read it from environment variable. useful, instead of passing plain-text passwords in command-line.
               ,"-keypass:env"   , "PDFR_KEY_PASSWORD"
               ,"-v"
               ,"-storetype"     , "PKCS12"
               ,"-keyalg"        , "RSA"
               ,"-keysize"       , "2048"
               ,"-sigalg"        , "SHA256withRSA"
               ,"-validity"      , "10000"
               ,"-dname"         , "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, S=Unknown, C=US"
               ];
  
  const exit_code = await run(keytool_path, args, env);
  console.log(`keytool finished with exit-code: ${exit_code}`);

  if(get_is_exists(properties.storeFile)){
    console.log(`SUCCESS: keystore-file (PKCS12) created successfully at: ${properties.storeFile}`);
  }
  
})();
