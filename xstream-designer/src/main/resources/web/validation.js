/* Fast authoring checks. This never starts workers, reads data or executes a Job. */
(function(root){
 function check(job,operators,report,variables={},validateParams=()=>{}){
  const issues=[],steps=job.steps||[],byId=new Map(steps.map(t=>[t.id,t])),consumers=new Map(),parents=new Map(),reachable=new Set();
  const issue=(severity,step,code,message)=>issues.push({severity,step,code,message});
  const error=(step,code,message)=>issue('error',step,code,message);
  const definition=t=>typeof t.operator==='object'?job.operators?.[t.operator.inline]:operators.find(o=>o.name+'@'+o.version===t.operator);
  const parse=(value,owner)=>{if(typeof value!=='string'||!/^([A-Za-z_][\w-]*)(?::([A-Za-z_][\w-]*))?$/.test(value)){error(owner,'invalid_binding','Invalid connection: '+String(value));return null;}const [step,port='result']=value.split(':');if(!byId.has(step)){error(owner,'missing_step','Unknown source step: '+step);return null;}return{step,port};};
  function bind(value,owner,contract){const b=parse(value,owner);if(!b)return null;const key=b.step+':'+b.port;const declaration=definition(byId.get(b.step));if(declaration&&!Object.hasOwn(declaration.outputs||{},b.port))error(owner,'missing_output','Unknown declared output port: '+key);else if(declaration&&contract){const match=root.PortViews.compatibility(declaration,b.port,{inputs:{target:contract}},'target');if(!match.allowed)error(owner,'incompatible_input',key+': '+match.reason);}if(consumers.has(key))error(owner,'duplicate_consumer',key+' already has a consumer');else consumers.set(key,owner);
   const upstream=report?.steps?.[b.step];if(upstream&&!upstream.diagnostics?.some(d=>d.severity==='error')){const d=upstream.outputs?.[b.port];if(!d)error(owner,'missing_output','Unknown output port: '+key);else if(contract){if(contract.cardinality&&d.structure?.cardinality!==contract.cardinality)error(owner,'incompatible_cardinality',key+' requires '+contract.cardinality+' Item cardinality');for(const kind of ['representations','formats'])if(!(kind==='representations'&&contract.representations?.includes('variant'))&&contract[kind]?.length&&d[kind]?.length&&!contract[kind].some(x=>d[kind].includes(x)))error(owner,'incompatible_input',key+' has incompatible '+kind);}}
   return b.step;
  }
  function resolve(value,step){if(typeof value==='string'&&/^\$[A-Za-z_][A-Za-z0-9_]*$/.test(value)){const name=value.slice(1);if(!Object.hasOwn(variables,name)){error(step,'missing_variable','Undefined variable: '+name);return value;}return variables[name];}if(Array.isArray(value))return value.map(x=>resolve(x,step));if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,resolve(v,step)]));return value;}
  if(!steps.length)error(null,'empty_job','Add at least one step');
  if(byId.size!==steps.length)error(null,'duplicate_step','Step IDs must be unique');
  const sinkSteps=steps.filter(t=>t.operator==='client_sink@1.0'||definition(t)?.role==='sink');
  if(!sinkSteps.length)error(null,'client_sink_unconnected','Connect Client Sink or add a Sink operator');
  for(const t of steps){
   const manifest=definition(t),source=t.operator==='item_source@1.0';
   if(!manifest)error(t.id,'unknown_operator','Operator not found: '+t.operator);
   const inputs=t.inputs||{},declared=source?{}:t.operator==='client_sink@1.0'?{[Object.keys(inputs)[0]||'result']:manifest?.inputs?.result}:manifest?.inputs;
   if((source||manifest?.role==='source')&&Object.keys(inputs).length)error(t.id,'source_inputs','Source cannot have inputs');
   if(sinkSteps.includes(t)&&steps.some(x=>Object.values(x.inputs||{}).some(v=>String(v).split(':')[0]===t.id)))error(t.id,'sink_output','Sink cannot have downstream consumers');
   if(t.operator==='client_sink@1.0'&&Object.keys(inputs).length!==1)error(t.id,'client_sink_unconnected','Client Sink requires exactly one connected input');
   if(declared){for(const p of Object.keys(declared))if(!Object.hasOwn(inputs,p))error(t.id,'missing_input','Input not connected: '+p);for(const p of Object.keys(inputs))if(!Object.hasOwn(declared,p))error(t.id,'unexpected_input','Undeclared input port: '+p);}
   parents.set(t.id,Object.entries(inputs).map(([p,v])=>bind(v,t.id,declared?.[p])).filter(Boolean));
   try{const params=resolve(t.params||{},t.id);if(source){if(typeof params.uri!=='string'||!params.uri)error(t.id,'invalid_params','item_source requires an Item URI');}else if(manifest?.parameters)validateParams(manifest.parameters,params,'params');}catch(e){error(t.id,'invalid_params',e.message);}
   const inferred=report?.steps?.[t.id];for(const d of inferred?.diagnostics||[])issue(d.severity,t.id,d.code,d.message);
   if(!inferred)issue('warning',t.id,'inference_pending','Output structure not yet verified');
  }
  function mark(id){if(reachable.has(id))return;reachable.add(id);for(const p of parents.get(id)||[])mark(p);}
  for(const t of sinkSteps)mark(t.id);
  for(const t of steps)if(!reachable.has(t.id))error(t.id,'disconnected_step','No dataflow path from this step to a Sink');
  const visiting=new Set(),done=new Set();function cycle(id){if(visiting.has(id)){error(id,'cycle','Dataflow contains a cycle');return;}if(done.has(id))return;visiting.add(id);for(const p of parents.get(id)||[])cycle(p);visiting.delete(id);done.add(id);}for(const t of steps)cycle(t.id);
  return issues;
 }
 root.WorkflowValidation=Object.freeze({check});
})(globalThis);
