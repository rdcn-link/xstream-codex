/* Interface Views belong to operator declarations; inference cannot change their labels. */
(function(root){
 'use strict';
 const paths={
  bytes:'M7 4H4v16h3M17 4h3v16h-3M10 8v8m4-8v8',
  records:'M3 4h18v16H3zM3 9h18M3 14h18M9 4v16',
  array:'m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5',
  items:'M8 3h9l4 4v13H8zM17 3v5h4M4 7H3v14h13',
  sequence:'M3 7h5v5H3zM16 12h5v5h-5zM8 9h4v6h4m-3-3 3 3-3 3',
  variable:'M3 6h3c5 0 7 12 12 12h3m-3-3 3 3-3 3M3 18h3c2 0 4-3 6-6s4-6 6-6h3m-3-3 3 3-3 3',
  pending:'M4 12h2m5 0h2m5 0h2',
  other:'m12 3 9 9-9 9-9-9 9-9Z'
 };
 function output(definition,port){const value=definition?.outputs?.[port]?.view;return typeof value==='string'?value:null;}
 function input(definition,port){const values=definition?.inputs?.[port]?.representations;return Array.isArray(values)&&values.length===1?values[0]:null;}
 function outputs(definition){return definition?.outputs&&typeof definition.outputs==='object'?Object.keys(definition.outputs):[];}
 function badge(view){return {path:paths[view]||paths[view?'other':'pending'],label:view==='variable'?'Variable View (可变类型)':view?'View: '+view:'Loading port declaration',view:view||'pending'};}
 function compatibility(source,outputPort,target,inputPort){
  const from=output(source,outputPort),to=input(target,inputPort);
  if(!from||!to)return {allowed:false,reason:'Port type declaration is unavailable. Reload the operator repository and try again.'};
  if(from==='variable'||to==='variable'||from===to)return {allowed:true};
  return {allowed:false,reason:'Incompatible port types: '+from+' → '+to+'. Choose an operator with a matching input View.'};
 }
 root.PortViews={output,input,outputs,badge,compatibility};
})(globalThis);
