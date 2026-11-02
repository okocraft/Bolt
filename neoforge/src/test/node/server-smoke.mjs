import { createReadStream, createWriteStream } from 'node:fs';
import { access, mkdir, copyFile, cp, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { join, resolve, sep } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Readable, Transform } from 'node:stream';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const root=fileURLToPath(new URL('../../../',import.meta.url));
const workspace=resolve(root,'../..');
const work=join(root,'build/smoke');
const server=join(work,'server');
const mods=join(server,'mods');
const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
async function exists(file){try{await access(file);return true;}catch{return false;}}
async function download(url,file,expected){
    const response=await fetch(url,{signal:AbortSignal.timeout(240000)});
    if(!response.ok)throw Error('Download failed: '+response.status+' '+url);
    const hash=createHash('sha256');
    const digest=new Transform({transform(chunk,encoding,next){hash.update(chunk);next(null,chunk);}});
    await pipeline(Readable.fromWeb(response.body),digest,createWriteStream(file));
    if(expected)assert.equal(hash.digest('hex'),expected,'Publisher digest differs');
}
async function obtain(file,target,url,expected){
    if(await exists(file)){await copyFile(file,target);if(expected){const hash=createHash('sha256');for await(const chunk of createReadStream(target))hash.update(chunk);assert.equal(hash.digest('hex'),expected);}}
    else await download(url,target,expected);
}
await mkdir(mods,{recursive:true});
if(!await exists(join(server,'libraries/net/neoforged/neoforge/26.2.0.88/unix_args.txt'))){
    const local=join(workspace,'WorldGuard-NeoForge/build/smoke/server/libraries');
    if(await exists(local))await cp(local,join(server,'libraries'),{recursive:true});
    await download('https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.88/neoforge-26.2.0.88-installer.jar',join(work,'installer.jar'));
    execFileSync(java,['-jar',join(work,'installer.jar'),'--installServer'],{cwd:server,stdio:'inherit',windowsHide:true,timeout:240000});
}
for(const [repo,name] of [['WorldGuard-NeoForge','worldguard-7.0.19-neoforge.jar'],['CoreProtectNeo','coreprotect-24.1-neoforge-26.2.jar']])
    await copyFile(join(workspace,repo,'build/libs',name),join(mods,name));
await copyFile(join(root,'build/libs/Bolt-1.2-neoforge-26.2.jar'),join(mods,'Bolt-1.2-neoforge-26.2.jar'));
await copyFile(join(root,'build/testmods/bolt-test.jar'),join(mods,'bolt-test.jar'));
const baseMods=join(workspace,'WorldGuard-NeoForge/build/smoke/server/mods');
await obtain(join(baseMods,'worldedit-neoforge-mc26.2-7.4.5-dist.jar'),join(mods,'worldedit-neoforge-mc26.2-7.4.5-dist.jar'),'https://maven.enginehub.org/repo/com/sk89q/worldedit/worldedit-neoforge-mc26.2/7.4.5/worldedit-neoforge-mc26.2-7.4.5-dist.jar');
await obtain(join(baseMods,'LuckPerms-NeoForge-5.5.85.jar'),join(mods,'LuckPerms-NeoForge-5.5.85.jar'),'https://cdn.modrinth.com/data/Vebnzrzj/versions/cLEqLHhz/LuckPerms-NeoForge-5.5.85.jar');
const assets=JSON.parse(await readFile(new URL('./dqm-assets.json',import.meta.url),'utf8'));
for(const asset of assets)await rm(join(mods,asset.name),{force:true});
const actualServer=await realpath(server), actualRoot=await realpath(root);
assert(actualServer.startsWith(actualRoot+sep),'Disposable test server is outside build workspace');
for(const relative of ['world','config/bolt']){
    const target=resolve(actualServer,relative);
    assert(target.startsWith(actualServer+sep),'Invalid disposable data path');
    await rm(target,{recursive:true,force:true});
}
await writeFile(join(server,'ops.json'),'[]\n');
await writeFile(join(server,'eula.txt'),'eula=true\n');
await writeFile(join(server,'server.properties'),'server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=2\npause-when-empty-seconds=-1\nlevel-type=minecraft:flat\n');
await writeFile(join(server,'user_jvm_args.txt'),'-Dfile.encoding=UTF-8\n-Dstdout.encoding=UTF-8\n-Dstderr.encoding=UTF-8\n-Xms256M\n-Xmx'+(process.env.BOLT_TEST_HEAP||'2G')+'\n');
async function boot(restart,label=""){
    const args='libraries/net/neoforged/neoforge/26.2.0.88/'+(process.platform==='win32'?'win_args.txt':'unix_args.txt');
    const child=spawn(java,['-Dbolt.test.restart='+restart,'@user_jvm_args.txt','@'+args,'nogui'],{cwd:server,stdio:['pipe','pipe','pipe'],windowsHide:true});
    const file=createWriteStream(join(work,'server-output'+label+(restart?'-restart':'')+'.log'));
    let initialized=false,ready=false,passed=false,failed=false,stopped=false;
    const markers=new Set(restart?['BOLT_RESTART_TESTS_PASSED']:['BOLT_PROTECTION_TESTS_PASSED','BOLT_COMMAND_TESTS_PASSED','BOLT_INTEGRATION_TESTS_PASSED']);
    if (!restart && label !== '-vanilla') markers.add('BOLT_DQM_CONTAINER_TESTS_PASSED');
    const timeout=setTimeout(()=>child.kill(),300000);
    for(const stream of [child.stdout,child.stderr])createInterface({input:stream}).on('line',line=>{
        file.write(line+'\n');console.log(line);
        initialized ||= line.includes('Bolt NeoForge initialized');ready ||= line.includes('Done (');
        failed ||= line.includes('BOLT_SERVER_TESTS_FAILED');passed ||= line.includes('BOLT_SERVER_TESTS_PASSED');
        for(const marker of markers)if(line.includes(marker))markers.delete(marker);
        if(ready&&passed&&!stopped){stopped=true;child.stdin.write('stop\n');}
    });
    try{const [code]=await once(child,'close');assert(initialized&&ready,'Packaged Bolt did not initialize');assert(passed&&!failed,'Bolt fixture failed');assert.equal(markers.size,0,'Missing fixture completion '+[...markers]);assert.equal(code,0,'Server failed to stop normally');}
    finally{clearTimeout(timeout);if(child.exitCode===null&&child.signalCode===null)child.kill();file.end();}
}
if (!process.argv.includes('--dqm-only')) await boot(false,'-vanilla');
for(const relative of ['world','config/bolt'])await rm(resolve(actualServer,relative),{recursive:true,force:true});
await Promise.all(assets.map(asset=>obtain(join(workspace,'.tools/multimc-setup/mods',asset.name),join(mods,asset.name),asset.url,asset.sha256)));
await boot(false);
await boot(true);
console.log('BOLT_PACKAGED_DQM_AND_RESTART_PASSED');
