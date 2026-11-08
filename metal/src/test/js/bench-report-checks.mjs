// Offline DOM contract checks for the report; no browser, network or additional dependencies.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class Element {
  constructor(tag){this.tag=tag;this.children=[];this.textContent='';this.listeners={};}
  append(...children){this.children.push(...children);}
  replaceChildren(...children){this.children=children;}
  addEventListener(type,callback){this.listeners[type]=callback;}
}
const html=fs.readFileSync(new URL('../../main/resources/siliconshaders/bench/report.html',import.meta.url),'utf8');
const script=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].at(-1)[1];
const row={scene:'land',variant:'standard',fps:100,averageFrameMs:10,p95Ms:11,framebuffer:[1920,1200],measurementValid:true,
  exclusiveFullscreen:true,vsync:false,effectiveFpsLimit:2147483647,sunSettings:{enabled:true},tuningSettings:{renderScale:0},cameraSettings:{bloom:0},lookSettings:{exposure:1}};
const report={format:2,suiteVersion:1,status:'ok',siliconshaders:'test',system:{chip:'Apple M1',gpuCores:8},results:[row],diagnostics:[],world:{seed:1},spots:{land:[0,100,0]},options:{renderDistance:12}};
const ids=Object.fromEntries(['data','files','warning','machines','rows','details'].map(id=>[id,new Element(id)]));
ids.data.textContent=JSON.stringify(report);
const document={getElementById:id=>ids[id],createElement:tag=>new Element(tag)};
vm.runInNewContext(script,{document,JSON,Number,Object,Array,Error});
assert.equal(ids.rows.children.length,1);
async function load(value){await ids.files.listeners.change({target:{files:[{name:'test.json',size:100,text:async()=>JSON.stringify(value)}],value:'x'}});}
await load(structuredClone(report));
assert.equal(ids.rows.children.length,2);assert.equal(ids.rows.children[0].children.at(-1).textContent,'ja');
const wrong=structuredClone(report);wrong.results[0].framebuffer=[2560,1600];await load(wrong);
assert.equal(ids.rows.children.at(-1).children.at(-1).textContent,'abweichend');
await load({broken:true});assert.match(ids.warning.textContent,/Kein SiliconShaders-Bericht/);
console.log('PASS: offline report renders, compares matching reports, detects resolution mismatch and rejects invalid imports');
