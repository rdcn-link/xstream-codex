const assert=require('node:assert/strict');
require('../xstream-designer/src/main/resources/web/port-views.js');
const definition={inputs:{source:{representations:['records']}},outputs:{fixed:{view:'bytes'},dynamic:{view:'variant',view_from:{input:'source'}}}};
assert.deepEqual(PortViews.outputs(definition),['fixed','dynamic']);
assert.equal(PortViews.output(definition,'fixed'),'bytes');
assert.equal(PortViews.output(definition,'dynamic'),'variant');
assert.equal(PortViews.input(definition,'source'),'records');
// Supplying different schema reports cannot alter the interface, including variant ports.
for(const actual of ['records','bytes','array','items']){
 global.report={steps:{producer:{outputs:{dynamic:{representations:[actual]}}}}};
 assert.equal(PortViews.output(definition,'dynamic'),'variant');
}
assert.equal(PortViews.output(null,'result'),null);
console.log('Declared fixed and variant port Views are independent of inference and connections');
