"""Render bilingual benchmark figures directly from measured trials (Agg backend)."""
from pathlib import Path
import statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
import numpy as np

METHODS = ['airflow-file', 'llm-tools', 'xstream-isolated', 'xstream-fused']
COLORS = ['#64748b', '#a855f7', '#0891b2', '#10b981']

def render(data, output):
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    keys = [(w,n) for w in data['config']['workflows'] for n in data['config']['rows']]
    measured = [dict(s) for s in data['samples'] if not s['warmup']]
    for s in measured:
        if s['method'].startswith('xstream'):
            s['operator_processes'] = sum(u.get('process_count',1) for u in s.get('execution_units',[]) if u['mode']!='host_stream_source')
        else: s['operator_processes'] = len(s.get('steps',[]))
    def group(w,n,m):
        return [s for s in measured if (s['workflow'],s['input_rows'],s['method']) == (w,n,m)]
    for zh in (False, True):
        suffix = '_zh' if zh else ''
        font = FontProperties(fname='/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc') if zh else FontProperties()
        labels = ['Airflow 文件', '模型工具', 'XStream 独立', 'XStream 合并'] if zh else ['Airflow files','Model tools','XStream isolated','XStream fused']
        def finish(fig, ax, name, title, ylabel):
            ax.set_title(title, fontproperties=font, pad=16)
            ax.set_ylabel(ylabel, fontproperties=font)
            ax.set_xticks(np.arange(len(keys)), [f'{w}\n{n:,}' for w,n in keys])
            ax.grid(axis='y', alpha=.18); ax.set_axisbelow(True)
            ax.spines[['top','right']].set_visible(False)
            if ax.get_legend_handles_labels()[0]: ax.legend(prop=font, fontsize=9, loc='upper left', bbox_to_anchor=(1.01,1))
            fig.tight_layout(); fig.savefig(output/(name+suffix+'.svg'), bbox_inches='tight'); plt.close(fig)
        for name, field, title, unit, scale in [
            ('wall-time','wall_s', '作业总耗时' if zh else 'End-to-end job time','秒' if zh else 'Seconds',1),
            ('first-data','first_data_s','首批最终结果延迟' if zh else 'First final-output data','秒' if zh else 'Seconds',1),
            ('process-count','operator_processes','算子进程数（不含框架及宿主数据源）' if zh else 'Operator processes (excluding frameworks / host sources)','个' if zh else 'Processes',1),
            ('file-volume','intermediate_payload_file_bytes','中间数据文件逻辑写入量' if zh else 'Logical intermediate data written','MiB',1048576)]:
            fig,ax=plt.subplots(figsize=(11,4.5)); width=.19
            for i,m in enumerate(METHODS):
                for j,(w,n) in enumerate(keys):
                    vals=[s[field]/scale for s in group(w,n,m) if s['state']=='succeeded' and s.get(field) is not None]
                    if not vals: continue
                    median=statistics.median(vals); x=j+(i-1.5)*width
                    ax.bar(x,median,width,color=COLORS[i],label=labels[i] if j==0 else None)
                    ax.errorbar(x,median,yerr=[[median-min(vals)],[max(vals)-median]],fmt='none',ecolor='#334155',capsize=3)
                    ax.annotate(f'{median:.2f}',(x,max(vals)),xytext=(0,5),textcoords='offset points',ha='center',fontsize=8)
            finish(fig,ax,name,title,unit)
        fig,ax=plt.subplots(figsize=(11,4.5)); width=.19
        for i,m in enumerate(METHODS):
            good=[sum(s['state']=='succeeded' for s in group(w,n,m)) for w,n in keys]
            bad=[len(group(w,n,m))-g for (w,n),g in zip(keys,good)]
            x=np.arange(len(keys))+(i-1.5)*width
            ax.bar(x,good,width,label=labels[i],color=COLORS[i])
            ax.bar(x,bad,width,bottom=good,color=COLORS[i],hatch='///',alpha=.4)
            for xx,g,b in zip(x,good,bad): ax.text(xx,g+b+.04,f'{g}/{g+b}',ha='center',fontsize=8)
        finish(fig,ax,'completion','成功次数 / 正式运行次数（斜线为失败）' if zh else 'Successful / measured trials (hatched = failed)','次数' if zh else 'Trials')
        fig,ax=plt.subplots(figsize=(11,4.5)); bottom=np.zeros(len(keys))
        for field,label,color in [('model_api_s','API 等待' if zh else 'API wait','#a855f7'),('tool_batches_s','工具批次' if zh else 'Tool batches','#0891b2'),('other','其他调度' if zh else 'Other scheduling','#94a3b8')]:
            vals=[]
            for w,n in keys:
                good=[s for s in group(w,n,'llm-tools') if s['state']=='succeeded']
                def value(s): return max(0,s['wall_s']-s.get('model_api_s',0)-s.get('tool_batches_s',0)) if field=='other' else s.get(field,0)
                vals.append(statistics.mean(map(value,good)) if good else 0)
            ax.bar(np.arange(len(keys)),vals,.55,bottom=bottom,label=label,color=color); bottom+=vals
        finish(fig,ax,'model-breakdown','模型编排耗时分解（成功样本均值）' if zh else 'Model orchestration breakdown (successful means)','秒' if zh else 'Seconds')
