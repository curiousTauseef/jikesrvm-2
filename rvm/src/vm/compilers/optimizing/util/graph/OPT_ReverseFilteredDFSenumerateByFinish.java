
class OPT_ReverseFilteredDFSenumerateByFinish extends OPT_ReverseDFSenumerateByFinish {

    private final OPT_GraphEdgeFilter filter;

    OPT_ReverseFilteredDFSenumerateByFinish(OPT_Graph net, 
					    OPT_GraphNodeEnumeration nodes,
					    OPT_GraphEdgeFilter filter)
    {
	super(net, nodes);
	this.filter = filter;
    }

    protected OPT_GraphNodeEnumeration getConnected (OPT_GraphNode n) {
	return filter.inNodes( n );
    }

}
