package TRIPS.PDFExtractor;

import java.util.HashMap;
import java.util.Map;

import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLString;
import TRIPS.util.cwc.InvalidArgument;
import TRIPS.util.cwc.UnknownObject;

/** Something that can be referred to in a KQML message by its ID. */
public interface HasID {
  /** Get the unique, case-insensitive string that identifies this object. */
  String getID();
  /** Return a KQML representation of this object. */
  KQMLObject toKQML();

  // static stuff
  
  class NextID { protected static int nextID = 1; } // implicit final workaround
  Map<String,HasID> byID = new HashMap<String,HasID>();

  /** Put an object that already has an ID into the table. */
  public static void put(HasID o) { byID.put(o.getID().toLowerCase(), o); }

  /** Generate an ID for a new object, put it in the table, and return the ID.
   * The caller should arrange to make o.getID() return the same value.
   */
  public static String getNextIDAndPut(HasID o) {
    String id = o.getClass().getSimpleName().toLowerCase() + NextID.nextID;
    NextID.nextID++;
    byID.put(id, o);
    return id;
  }

  /** Do we have an object with this ID? */
  public static boolean has(String id) { return byID.containsKey(id); }

  /** Get an object by its ID and cast to the expected type. */
  public static <T extends HasID> T get(String id, Class<T> t) throws UnknownObject, InvalidArgument {
    id = id.toLowerCase();
    if (!byID.containsKey(id)) {
      throw new UnknownObject(id);
    }
    HasID o = byID.get(id);
    if (!t.isInstance(o)) {
      throw new InvalidArgument("get", ":id", "ID of a " + t.getSimpleName() + " object", new KQMLString(id));
    }
    return t.cast(o);
  }

  /** Forget an ID and the object it identifies. */
  public static void remove(String id) { byID.remove(id); }

  /** Forget an object and the ID that identifies it. */
  public static void remove(HasID o) { byID.remove(o.getID()); }

  /** Forget all IDs and the objects they identify. */
  public static void clear() { byID.clear(); }
}
