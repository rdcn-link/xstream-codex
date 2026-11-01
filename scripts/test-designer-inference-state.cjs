const assert=require('node:assert/strict');
require('../xstream-designer/src/main/resources/web/inference-state.js');
const {keys,retain}=globalThis.InferenceChanges;
const job={name:'Branches',steps:[
 {id:'read',operator:'read_item@1.0',inputs:{},params:{uri:'dacp://example/weather/observations'}},
 {id:'filter',operator:'filter@1',inputs:{source:'read'},params:{condition:'true'}},
 {id:'tail',operator:'convert@1',inputs:{source:'filter'},params:{}},
 {id:'other',operator:'source@1',inputs:{},params:{value:1}}
],outputs:{result:'tail'}};
const request=(job,variables='{}')=>({job_yaml:JSON.stringify(job),variables_yaml:variables});
const before=keys(request(job)),report={steps:Object.fromEntries(job.steps.map(t=>[t.id,{outputs:{result:{certainty:'known'}},diagnostics:[]}])),outputs:{}};
const changed=(j,vars)=>Object.keys(keys(request(j,vars))).filter(id=>keys(request(j,vars))[id]!==before[id]);
let draft=structuredClone(job);draft.steps.pop();assert.deepEqual(changed(draft),[]);
draft=structuredClone(job);draft.steps[2].params.format='csv';assert.deepEqual(changed(draft),['tail']);
draft=structuredClone(job);draft.steps[1].params.condition='value > 10';assert.deepEqual(changed(draft),['filter','tail']);
let kept=retain(report,before,keys(request(draft)),draft);assert.deepEqual(Object.keys(kept.steps),['read','other']);assert.deepEqual(kept.outputs,{});
draft=structuredClone(job);draft.steps[3].params.value=2;assert.deepEqual(changed(draft),['other']);
draft=structuredClone(job);draft.steps.splice(2,1);draft.outputs={result:'filter'};kept=retain(report,before,keys(request(draft)),draft);assert.deepEqual(Object.keys(kept.steps),['read','filter','other']);assert.equal(kept.outputs.result,report.steps.filter.outputs.result);
draft=structuredClone(job);draft.name='Renamed';draft.steps.reverse();for(const t of draft.steps){t.title='Renamed';}draft.steps.find(t=>t.id==='filter').inputs.source='read:result';assert.deepEqual(changed(draft),[]);
assert.deepEqual(keys(request(job,'UNUSED: 5')),before);
draft=structuredClone(job);draft.steps[1].params.condition='$CONDITION';const a=keys(request(draft,'CONDITION: true')),b=keys(request(draft,'CONDITION: false'));assert.equal(a.read,b.read);assert.equal(a.other,b.other);assert.notEqual(a.filter,b.filter);assert.notEqual(a.tail,b.tail);
console.log('PASS: step deletion, branch-local changes, descendants, metadata, reordering, default ports, output rebinding and variables');
