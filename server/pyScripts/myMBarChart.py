import pandas as pd
import argparse
import plotly.graph_objs as go
import plotly


df = pd.read_csv("deal.txt", sep='\t', index_col=0)
columnNames=df.columns
plotRow=df.ix["data"]
bar = go.Bar(
    x=columnNames,
    y=plotRow,
)
data = [bar]
layout = go.Layout(
    title="Bar Plot",
    width=900,
    height=700,
    xaxis=dict(
        showgrid=False,
        ticks='outside',
        zeroline=False,
        showline=True,
        tickangle=45,
    ),
    yaxis=dict(
        title="Enriched Metabolite Number",
        showgrid=False,
        zeroline=False,
        ticks='outside',
        showline=True,
    ),
    hovermode="closest",
    margin=dict(
        b=250
)
)
config = dict(
    displaylogo=False,
    showLink=False,
)
fig = dict(data=data, layout=layout)
# plotly.offline.plot(fig, filename="basic-scatter.html", config=config)
a = plotly.offline.plot(fig, filename="basic-scatter.html", config=config, output_type="div", include_plotlyjs=False)
f = open('div.txt', 'w')
f.write(a)
f.close()