---
description: Debug frontend and backend issues using Playwright and system diagnostics
---

# Debug Command

Systematic debugging guide for Lemline frontend and backend issues.

## Usage

```
/debug [target] [description]
```

**Targets:**
- `frontend` - Debug React/TypeScript frontend issues
- `backend` - Debug Kotlin/Quarkus backend issues
- `fullstack` - Debug issues spanning both frontend and backend
- `workflow` - Debug workflow execution or visualization issues
- `auth` - Debug authentication/authorization issues

## Instructions

Parse the user's command to determine the debug target:

### 1. Frontend Debugging (`/debug frontend <description>`)

**Example:** `/debug frontend login button not working`

**Steps:**

1. **Navigate to the page:**
   ```
   mcp__playwright__browser_navigate to http://localhost:5173/<page-path>
   ```

2. **Check console for errors:**
   ```
   mcp__playwright__browser_console_messages
   ```
   Look for:
   - React errors (component rendering, hooks)
   - Vite HMR connection issues
   - API call failures
   - TypeScript errors

3. **Inspect network requests:**
   ```
   mcp__playwright__browser_network_requests
   ```
   Check:
   - Failed API calls (4xx, 5xx status codes)
   - Missing Authorization headers
   - CORS errors
   - Timeout issues

4. **Take screenshot for visual issues:**
   ```
   mcp__playwright__browser_take_screenshot
   ```

5. **Inspect DOM structure:**
   ```
   mcp__playwright__browser_snapshot
   ```
   Check:
   - Missing elements
   - Incorrect Catalyst component usage
   - Accessibility tree

6. **Check browser state:**
   ```
   mcp__playwright__browser_evaluate with function: () => ({
     token: localStorage.getItem('auth_token'),
     user: localStorage.getItem('user'),
     url: window.location.href
   })
   ```

7. **Report findings** with:
   - Root cause analysis
   - Fix suggestions with file paths
   - Related code sections to check

8. **Delegate fixes to specialized agents:**
   - **Complex TypeScript issues** → Use `frontend-developer` agent
   - **TanStack Query problems** → Use `frontend-developer` agent
   - **Catalyst UI issues** → Use `ui-designer` agent
   - **React Flow visualization** → Use `frontend-developer` or `ui-designer` agent

**Common Frontend Issues:**

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Blank page | React error in render | Check console for stack trace |
| API 401 | Missing/expired JWT | Check localStorage token, verify backend |
| Type errors | Outdated workflow types | Run `./gradlew :lemline-common:copyToFrontend` |
| Slow rendering | React re-renders | Use React.memo, useMemo, useCallback |
| Form not submitting | Validation error | Check Zod schema, console errors |

### 2. Backend Debugging (`/debug backend <description>`)

**Example:** `/debug backend workflow creation returning 500 error`

**Steps:**

1. **Check application logs:**
   ```bash
   docker logs -f lemline-backend --tail=100
   ```
   Look for:
   - Stack traces with trace ID
   - SQL errors
   - NullPointerException
   - Validation errors

2. **Verify service health:**
   ```bash
   curl http://localhost:8080/health
   ```

3. **Test API endpoint directly:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/<endpoint> \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <token>" \
     -d '{"test": "data"}'
   ```

4. **Check database state:**
   ```bash
   docker exec -it lemline-postgres psql -U lemline -d lemline \
     -c "SELECT * FROM <table> ORDER BY created_at DESC LIMIT 10;"
   ```

5. **Verify Flyway migrations:**
   ```bash
   cd lemline-backend && ./gradlew quarkusFlywayInfo
   ```

6. **Check Prometheus metrics:**
   ```bash
   curl http://localhost:8080/metrics | grep <metric-name>
   ```

7. **Report findings** with:
   - Exception details with trace ID
   - SQL queries if database-related
   - Fix suggestions with file paths
   - Migration needs if schema-related

8. **Delegate fixes to specialized agents:**
   - **Architecture/API design** → Use `software-architect` agent
   - **Complex Kotlin issues** → Use `backend-developer` agent
   - **KOOG AI problems** → Use `backend-developer` or `ai-engineer` agent
   - **Database schema design** → Use `software-architect` agent
   - **Performance optimization** → Use `backend-developer` agent

**Common Backend Issues:**

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 500 Internal Server Error | Uncaught exception | Check logs for trace ID, fix in service layer |
| 401 Unauthorized | JWT validation failed | Verify secret key in Vault, check token expiration |
| 404 Not Found | Missing entity | Check repository query, verify ID exists in DB |
| Database connection error | PostgreSQL not running | `docker-compose up -d postgres` |
| Flyway migration failed | Invalid SQL | Check migration file, use `quarkusFlywayRepair` |

### 3. Full-Stack Debugging (`/debug fullstack <description>`)

**Example:** `/debug fullstack user registration flow not completing`

**Steps:**

1. **Start with frontend** (trigger the action):
   - Navigate to page
   - Fill form via Playwright
   - Capture network request

2. **Extract trace ID** from response:
   ```
   mcp__playwright__browser_network_requests
   ```
   Look for `X-Trace-Id` header

3. **Correlate with backend** logs:
   ```bash
   docker logs lemline-backend | grep <trace-id>
   ```

4. **Check database** for state:
   ```bash
   docker exec -it lemline-postgres psql -U lemline -d lemline \
     -c "SELECT * FROM users WHERE email = 'user@example.com';"
   ```

5. **Full request flow analysis:**
   ```
   Frontend Action → Network Request → Backend Resource → Service → Repository → Database
   ↓                 ↓                ↓                  ↓          ↓            ↓
   Button click      POST /register  AuthResource       UserService UserRepo    users table
   ```

6. **Report findings** with:
   - Flow diagram showing where it breaks
   - Frontend and backend fixes needed
   - Data inconsistencies found

7. **Delegate fixes to specialized agents based on where the issue is:**
   - **Frontend issues** → Use `frontend-developer` or `ui-designer` agent
   - **Backend issues** → Use `backend-developer` or `software-architect` agent
   - **Workflow issues** → Use `workflow-expert` agent
   - **AI/KOOG issues** → Use `ai-engineer` agent

### 4. Workflow Debugging (`/debug workflow <description>`)

**Example:** `/debug workflow visualization not rendering nodes`

**Steps:**

1. **Check workflow definition:**
   - Read workflow JSON file
   - Validate against CNCF spec using workflow-expert
   - Check for syntax errors

2. **Frontend rendering** (if visualization issue):
   - Navigate to workflow page
   - Check console for React Flow errors
   - Inspect network request for workflow data
   - Check if workflow types are generated

3. **Backend execution** (if runtime issue):
   - Check logs for workflow execution errors
   - Verify JQ expression evaluation
   - Check authentication if HTTP calls fail
   - Verify retry policy execution

4. **Report findings** with:
   - Workflow syntax errors (if any)
   - React Flow node/edge issues
   - Execution errors with task names
   - Fix suggestions

5. **Delegate fixes to specialized agents:**
   - **Workflow DSL issues** → Use `workflow-expert` agent
   - **React Flow visualization** → Use `frontend-developer` or `ui-designer` agent
   - **Workflow execution backend** → Use `backend-developer` agent
   - **AI-generated workflows** → Use `ai-engineer` agent

### 5. Auth Debugging (`/debug auth <description>`)

**Example:** `/debug auth users getting logged out randomly`

**Steps:**

1. **Check JWT token:**
   ```
   mcp__playwright__browser_evaluate with function:
   () => {
     const token = localStorage.getItem('auth_token')
     if (!token) return { error: 'No token found' }
     const payload = JSON.parse(atob(token.split('.')[1]))
     return { payload, expired: payload.exp * 1000 < Date.now() }
   }
   ```

2. **Verify Vault secret:**
   ```bash
   docker exec -it lemline-vault vault kv get secret/jwt/private-key
   ```

3. **Test JWT validation:**
   ```bash
   curl -X GET http://localhost:8080/api/v1/auth/test \
     -H "Authorization: Bearer <token>"
   ```

4. **Check password hashing** (if login fails):
   - Verify PBKDF2 format in database
   - Check iterations count (should be 100k)
   - Test with known-good credentials

5. **OAuth flow** (if third-party login):
   - Check callback URL configuration
   - Verify OAuth client credentials in Vault
   - Check state parameter for CSRF protection
   - Test with Cloudflare Tunnel or ngrok

6. **Report findings** with:
   - Token expiration info
   - Vault configuration issues
   - OAuth flow step that fails
   - Fix suggestions

7. **Delegate fixes to specialized agents:**
   - **JWT/Vault backend** → Use `backend-developer` agent
   - **OAuth architecture** → Use `software-architect` agent
   - **Frontend auth flow** → Use `frontend-developer` agent
   - **Auth UI/forms** → Use `ui-designer` agent

## Tools Reference

**Playwright MCP Tools:**
- `mcp__playwright__browser_navigate` - Navigate to URL
- `mcp__playwright__browser_console_messages` - Get console logs
- `mcp__playwright__browser_network_requests` - Get network activity
- `mcp__playwright__browser_snapshot` - Get accessibility tree
- `mcp__playwright__browser_take_screenshot` - Visual debugging
- `mcp__playwright__browser_evaluate` - Run JavaScript
- `mcp__playwright__browser_click` - Interact with elements
- `mcp__playwright__browser_type` - Fill forms

**Bash Commands:**
- `docker logs <container>` - View container logs
- `docker exec -it <container> <command>` - Run command in container
- `curl` - Test API endpoints
- `psql` - Query PostgreSQL
- `./gradlew quarkusDev` - Run backend with live reload

## Best Practices

1. **Start with the symptom** - What is the user seeing/experiencing?
2. **Follow the data flow** - Frontend → API → Service → Database
3. **Use trace IDs** - Correlate frontend errors with backend logs
4. **Check the basics first:**
   - Is the service running? (`docker ps`)
   - Is the database accessible? (`docker exec ... psql`)
   - Is the token valid? (Check expiration)
5. **Isolate the issue:**
   - Frontend only? (Test with curl)
   - Backend only? (Check logs)
   - Database? (Query directly)
6. **Always validate fixes** - Re-run the failing scenario

## Common Debug Scenarios

### Scenario 1: "Nothing happens when I click the button"

```bash
/debug frontend button not responding
```

Checklist:
- [ ] Check console for JavaScript errors
- [ ] Verify event handler is attached (inspect in DevTools)
- [ ] Check if button is disabled
- [ ] Verify Catalyst Button component usage
- [ ] Check for overlaying elements (z-index issues)

### Scenario 2: "API returns 500 error"

```bash
/debug backend API returning 500
```

Checklist:
- [ ] Get trace ID from error response
- [ ] Search logs for trace ID
- [ ] Identify exception type and stack trace
- [ ] Check if database query failed
- [ ] Verify request payload matches DTO
- [ ] Check GlobalExceptionHandler mapping

### Scenario 3: "Workflow not executing"

```bash
/debug workflow execution failing
```

Checklist:
- [ ] Validate workflow syntax with workflow-expert
- [ ] Check backend logs for workflow execution errors
- [ ] Verify JQ expressions are valid
- [ ] Check authentication for HTTP tasks
- [ ] Verify retry policies aren't exhausted
- [ ] Check workflow state in database

## Documentation References

- **TROUBLESHOOTING.md** - `/docs/TROUBLESHOOTING.md` (common issues and solutions)
- **Backend Guides** - `/docs/backend/` (backend patterns and debugging)
- **Frontend Development** - `/docs/frontend/development/` (frontend patterns, debugging, and troubleshooting)
- **CLAUDE.md** - Debugging section in project root

## Agent Delegation Guide

After diagnosing the issue, **always delegate fixes to the appropriate specialized agent**:

### Frontend Issues
- **Complex TypeScript/React** → `frontend-developer`
- **UI/UX design, Catalyst** → `ui-designer`
- **TanStack Query, performance** → `frontend-developer`
- **React Flow visualization** → `frontend-developer` or `ui-designer`

### Backend Issues
- **Architecture, API design, schema** → `software-architect`
- **Kotlin implementation, reactive** → `backend-developer`
- **KOOG AI, workflow generation** → `ai-engineer`
- **Database optimization** → `backend-developer`

### Workflow Issues
- **CNCF workflow DSL** → `workflow-expert`
- **AI-generated workflows** → `ai-engineer`
- **Workflow execution** → `backend-developer`
- **Workflow visualization** → `frontend-developer` or `ui-designer`

### Cross-Cutting Issues
- **Full-stack features** → Start with `software-architect`, then delegate to others
- **Authentication/security** → `software-architect` (architecture), then `backend-developer` (implementation)
- **Performance** → `backend-developer` (backend) or `frontend-developer` (frontend)

**Example delegation:**
```
After debugging: "The issue is in the TanStack Query cache invalidation logic after workflow creation"
→ Delegate to: frontend-developer agent for fixing the cache invalidation pattern
```

## Notes

- Always capture trace IDs for correlation
- Take screenshots for visual issues
- Check both frontend console AND backend logs
- Verify services are running before deep debugging
- **After diagnosis, always delegate fixes to specialized agents** (see guide above)
