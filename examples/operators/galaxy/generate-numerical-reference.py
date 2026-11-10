import json
from pathlib import Path
import numpy as np
from scipy import stats,optimize,linalg
r=Path(__file__).resolve().parent;reg=json.loads((r/'extended-regressions.json').read_text());cases=[];rng=np.random.default_rng(20261109)
def fmt(x):return f'{0. if abs(x)<5e-7 else x:.6f}'
def add(n,a,params,expected):
 c=dict(operator=n,data=''.join('\t'.join(format(float(v),'.12g') for v in row)+'\n' for row in a),params=params,expected=expected,numeric_tolerance=1e-5);cases.append(c)
for repeat in range(6):
 x=rng.normal(size=(20+repeat,3));y=2.3+x@np.array([.4,-1.7,3.2])+rng.normal(size=len(x))*.2;a=np.column_stack([x,y]);coef,*_=np.linalg.lstsq(np.column_stack([np.ones(len(x)),x]),y,rcond=None);res=y-coef[0]-x@coef[1:];sse=res@res;total=np.sum((y-y.mean())**2)
 params=dict(response_column=4,predictor_columns=[1,2,3]);expected='term\tcoefficient\nintercept\t'+fmt(coef[0])+'\n'+''.join(f'column_{i+1}\t{fmt(coef[i+1])}\n' for i in range(3))+f'r_squared\t{fmt(1-sse/total)}\n';add('linear_regression',a,params,expected)
 partial=[]
 for j in range(3):
  xx=np.delete(x,j,axis=1);b=np.linalg.lstsq(np.column_stack([np.ones(len(x)),xx]),y,rcond=None)[0];reduced=np.sum((y-b[0]-xx@b[1:])**2);partial.append((reduced-sse)/reduced)
 add('partial_r_square',a,params,'column\tpartial_r_squared\n'+''.join(f'{j+1}\t{fmt(v)}\n' for j,v in enumerate(partial)))
 cov=np.cov(x,rowvar=False);e,vec=np.linalg.eigh(cov);order=np.argsort(-e);vec=vec[:,order]
 for j in range(3):
  k=np.argmax(np.abs(vec[:,j]));vec[:,j]*=1 if vec[k,j]>=0 else -1
 scores=(x-x.mean(axis=0))@vec;add('pca',x,{'columns':[1,2,3]},'row\tPC1\tPC2\tPC3\n'+''.join(f'{i+1}\t'+'\t'.join(fmt(v) for v in row)+'\n' for i,row in enumerate(scores)))
 sample=rng.normal(loc=repeat-2,scale=2,size=(18,1));test=stats.ttest_1samp(sample[:,0],popmean=.7);add('statistical_hypothesis_testing',sample,{'null_mean_x1000':700},f'n\tmean\tt_statistic\tdegrees_freedom\tp_value\n18\t{fmt(sample.mean())}\t{fmt(test.statistic)}\t17\t{fmt(test.pvalue)}\n')
 # Ridge logistic oracle uses SciPy's general optimizer, independent of Scala IRLS.
 z=rng.normal(size=(40,2));yy=(rng.random(40)<1/(1+np.exp(-(.3+z[:,0]-.7*z[:,1])))).astype(float);means=z.mean(axis=0);scales=z.std(axis=0);design=np.column_stack([np.ones(40),(z-means)/scales]);ridge=.2
 def objective(b):
  v=design@b;return np.logaddexp(0,v).sum()-yy@v+ridge*np.sum(b[1:]**2)/2
 def grad(b):return design.T@(1/(1+np.exp(-(design@b)))-yy)+ridge*np.r_[0,b[1:]]
 fit=optimize.minimize(objective,np.zeros(3),jac=grad,method='BFGS',options={'gtol':1e-9});assert np.max(np.abs(grad(fit.x)))<1e-6;slopes=fit.x[1:]/scales;intercept=fit.x[0]-slopes@means
 add('logistic_regression',np.column_stack([z,yy]),{'response_column':3,'predictor_columns':[1,2],'ridge_x1000':200},'term\tcoefficient\nintercept\t'+fmt(intercept)+'\n'+''.join(f'column_{i+1}\t{fmt(v)}\n' for i,v in enumerate(slopes)))
for n,p,data in [('linear_regression',{},'1\t2\n1\t3\n1\t4\n'),('linear_regression',{'response_column':3,'predictor_columns':[1,2]},'1\t2\t3\n2\t4\t4\n3\t6\t5\n4\t8\t6\n'),('pca',{},'NaN\t1\n2\t3\n'),('pca',{},'1\t1\n'),('logistic_regression',{},'1\t0\n2\t0\n3\t0\n'),('logistic_regression',{},'1\t0\n2\t2\n3\t1\n'),('statistical_hypothesis_testing',{},'1\n1\n'),('histogram',{},'Infinity\n'),('scatterplot',{},'1\n2\n')]:
 c=dict(operator=n,params=p,data=data)
 if c not in reg['negative']:reg['negative'].append(c)
old=json.loads((r/'upstream-numerical-cases.json').read_text())['cases'] if (r/'upstream-numerical-cases.json').exists() else [];reg['positive']=[c for c in reg['positive'] if c not in old]+cases;(r/'extended-regressions.json').write_text(json.dumps(reg,indent=2)+'\n');(r/'upstream-numerical-cases.json').write_text(json.dumps(dict(reference='Independent NumPy 2.x least-squares/eigh and SciPy 1.18 ttest_1samp/BFGS; deterministic seed20261109. These validate documented normalized mathematical methods, not R formatting.',cases=cases),indent=2)+'\n');print('Generated',len(cases),'independent numeric comparisons')
