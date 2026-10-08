package consulting.tornbergs.directory;
import jakarta.ws.rs.ext.*;
import jakarta.ws.rs.core.Response;
@Provider public class FailureMapper implements ExceptionMapper<Api.Failure> {
 public Response toResponse(Api.Failure e) {
  return Response.status(e.status).entity(new Api.Reply(null,null,null,"FAILED",e.code,e.getMessage(),null,null)).build();
 }
}
