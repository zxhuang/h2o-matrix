package ai.h2o.math;

import java.util.Iterator;

import org.apache.mahout.math.*;
import org.apache.mahout.math.function.DoubleDoubleFunction;
import org.apache.mahout.math.function.DoubleFunction;

import water.*;
import water.fvec.*;
import water.fvec.Vec.VectorGroup;

/**
 * Shows how to implement a vector type.
 */
public class H2OColumn extends H2OVector {
  Vec _vec;

  H2OColumn( Vec vec ) {
    super((int)vec.length());
    if( (int)vec.length() != vec.length() )
      throw new IllegalArgumentException("AbstractVector is limited to 2^31 in length");
    _vec = vec;
  }
  public H2OColumn(int size) {
    super(size);
    if( size > 100000 ) throw H2O.unimpl(); // Should split into chunks
    _vec = Vec.makeConSeq(0,size);
  }

  public H2OColumn(Vector r) {
    super(r.size());
    if( r instanceof H2OColumn ) {
      _vec = new Frame(((H2OColumn)r)._vec).deepSlice(null,null).vecs()[0];
    } else {
      AppendableVec av = new AppendableVec(VectorGroup.VG_LEN1.addVec());
      NewChunk nc = new NewChunk(av,0);
      for( Element element : r.all() )
        nc.addNum(element.get());
      Futures fs = new Futures();
      nc.close(0,fs);
      _vec = av.close(fs);
      fs.blockForPending();
    }
  }

  @Override protected Iterator<Element> iterator() {
    // this is a kind of slow iterator.
    // CNC - Speed Hack: return the same Element each time 'round
    return new Iterator<Element>() {
      private Chunk _c = _vec.chunkForRow(0);
      private int _i=-1, _len=(int)_vec.length();
      private Element _elem = new Element() {
          private Chunk iter() { return _c._start <= _i && _i < _c._start+_c._len ? _c : (_c = _vec.chunkForRow(_i)); }
          @Override public double get() { return iter().at(_i-_c._start); }
          @Override public int  index() { return _i; }
          @Override public void set(double value) { iter().set(_i-_c._start,value); }
        };
      @Override public boolean hasNext() { return _i+1<_len; }
      @Override public Element next() { _i++; return _elem; }
      @Override public void remove() { throw H2O.fail(); }
    };
  }

  // Single-element accessors.  Calling these likely indicates a huge performance bug.
  @Override public double getQuick(int index) { return _vec.at(index); }
  @Override public void setQuick(int index, double value) { _vec.set(index,value); }

  @Override public double minValue() { return _vec.min(); }
  @Override public double maxValue() { return _vec.max(); }

  @Override public double aggregate(DoubleDoubleFunction aggregator, DoubleFunction map) {
    if( _vec.length() == 0 ) return 0;

    // Quick check for aggregate being zero
    if( aggregator.isAssociativeAndCommutative() && !map.isDensifying() ) {
      // If the aggregator is associative and commutative and it's likeLeftMult
      // (fa(0, y) = 0), and there is at least one zero in the vector (size >
      // getNumNondefaultElements) and applying fm(0) = 0, the result gets
      // casacaded through the aggregation and the final result will be 0.
      if( aggregator.isLikeLeftMult() && _vec.length() > getNumNondefaultElements() )
        return 0;

      // If fm(0) = 0 and fa(x, 0) = x, we can skip all zero values.
      if( aggregator.isLikeRightPlus() ) {
        Iterator<Element> iterator = iterateNonZero();
        if( !iterator.hasNext() ) return 0;
      }
    }
    // Must use expensive iterator?  Or vec is sparse
    if( isSequentialAccess() ) return super.aggregate(aggregator,map);
    // Parallel/distributed aggregate call
    return new Aggregate(H2ODoubleDoubleFunction.map(aggregator),H2ODoubleFunction.map(map)).doAll(_vec)._res;
  }
  private static class Aggregate extends MRTask2<Aggregate> {
    final H2ODoubleDoubleFunction _reducer;
    final H2ODoubleFunction _mapper;
    Aggregate( H2ODoubleDoubleFunction reducer, H2ODoubleFunction mapper ) { _reducer = reducer; _mapper = mapper; }
    double _res;
    @Override public void map( Chunk c ) {
      H2ODoubleFunction mapper = _mapper;
      H2ODoubleDoubleFunction reducer = _reducer;
      double d = mapper.apply(c.at(0));
      for( int row=1; row<c._len; row++ )
        d = reducer.apply(d,mapper.apply(c.at(row)));
      _res = d;
    }
    @Override public void reduce( Aggregate A ) { _res = _reducer.apply(_res,A._res); }
  }

  @Override public double zSum() { return aggregate(H2ODoubleDoubleFunction.PLUS, H2ODoubleFunction.IDENTITY); }
}
