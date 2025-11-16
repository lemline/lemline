Please create comprehensive tests for: $ARGUMENTS

## Frontend Tests (Vitest + React Testing Library)

**Location & Naming:**
- Co-locate: `src/components/Foo.test.tsx` next to `src/components/Foo.tsx`
- Run: `npm run test` (watch) or `npm run test -- --run` (single)
- Debug: `npm run test -- Foo.test` or `npm run test -- -t "test name"`

**Setup test utilities** (`src/test/utils/test-utils.tsx`):
```typescript
import { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

export function renderWithProviders(
  component: ReactElement,
  { initialRoute = '/', queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
  }) } = {}
) {
  return render(
    <MemoryRouter initialEntries={[initialRoute]}>
      <QueryClientProvider client={queryClient}>
        {component}
      </QueryClientProvider>
    </MemoryRouter>
  )
}
```

**Mock TanStack Query hooks:**
```typescript
import { useIntegrations, useCreateIntegration } from '@/hooks/useIntegrations'
import { renderWithProviders } from '@/test/utils/test-utils'
import userEvent from '@testing-library/user-event'
import { screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/hooks/useIntegrations')

describe('IntegrationsPage', () => {
  beforeEach(() => {
    vi.mocked(useIntegrations).mockReturnValue({
      data: [{ id: '1', name: 'Test', apiUrl: 'https://api.test.com', authType: 'NONE', status: 'ACTIVE' }],
      isLoading: false,
      error: null,
    } as any)

    vi.mocked(useCreateIntegration).mockReturnValue({
      mutate: vi.fn(),
      mutateAsync: vi.fn(),
      isPending: false,
    } as any)
  })

  it('should display integration list', () => {
    renderWithProviders(<IntegrationsPage />)
    expect(screen.getByText('Test')).toBeInTheDocument()
  })

  it('should show loading state', () => {
    vi.mocked(useIntegrations).mockReturnValue({ isLoading: true } as any)
    renderWithProviders(<IntegrationsPage />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('should handle user interaction', async () => {
    const user = userEvent.setup()
    renderWithProviders(<IntegrationForm />)

    await user.type(screen.getByLabelText(/name/i), 'My Integration')
    await user.click(screen.getByRole('button', { name: /submit/i }))

    await waitFor(() => {
      expect(mockSubmit).toHaveBeenCalled()
    })
  })
})
```

**Frontend test checklist:**
- ✅ Loading states (`isLoading: true`)
- ✅ Error states (`error: new Error(...)`)
- ✅ User interactions with `userEvent` (NOT `fireEvent`)
- ✅ Form validation and submission
- ✅ Conditional rendering
- ✅ Clean up: `vi.clearAllMocks()` in `afterEach`

---

## Backend Tests (JUnit + RestAssured + Quarkus)

**Location & Naming:**
- Standard: `src/test/kotlin/com/lemline/FooTest.kt`
- Run: `./gradlew test --tests "FooTest"`
- Debug: `./gradlew test --tests "FooTest" --stacktrace`

**Setup test class with JWT tokens:**
```kotlin
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import jakarta.inject.Inject

@QuarkusTest
class IntegrationResourceTest {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var jwtService: JwtService

    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setup() {
        val user = User(
            email = "test-${System.currentTimeMillis()}@example.com",
            passwordHash = PasswordHasher.hash("password"),
            fullName = "Test User",
            role = UserRole.USER
        )
        userRepository.persist(user)
        userToken = jwtService.generateToken(user)

        val admin = User(
            email = "admin-${System.currentTimeMillis()}@example.com",
            passwordHash = PasswordHasher.hash("password"),
            fullName = "Admin User",
            role = UserRole.ADMIN
        )
        userRepository.persist(admin)
        adminToken = jwtService.generateToken(admin)
    }
}
```

**Create test data factory** (`src/test/kotlin/com/lemline/utils/TestDataFactory.kt`):
```kotlin
object TestDataFactory {
    fun createIntegrationRequest(
        name: String = "Test Integration",
        apiUrl: String = "https://api.example.com",
        authType: AuthType = AuthType.NONE,
        authCredentials: Map<String, Any>? = null,
        description: String? = null
    ) = CreateIntegrationRequest(name, apiUrl, authType, authCredentials, description)

    fun createUser(
        email: String = "test-${System.currentTimeMillis()}@example.com",
        role: UserRole = UserRole.USER
    ) = User(email, PasswordHasher.hash("password"), "Test User", role)
}
```

**Test examples:**
```kotlin
@Test
fun `should create integration with valid data`() {
    val request = TestDataFactory.createIntegrationRequest(
        name = "Slack",
        authType = AuthType.API_KEY,
        authCredentials = mapOf("apiKey" to "test-key")
    )

    given {
        contentType(ContentType.JSON)
        body(request)
        header("Authorization", "Bearer $userToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(201)
        body("id", notNullValue())
        body("name", equalTo("Slack"))
        body("authCredentials", nullValue()) // Must not expose
    }
}

@Test
fun `should return validation error for invalid data`() {
    val request = TestDataFactory.createIntegrationRequest(apiUrl = "not-a-url")

    given {
        contentType(ContentType.JSON)
        body(request)
        header("Authorization", "Bearer $userToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(400)
        body("violations[0].field", equalTo("apiUrl"))
    }
}
```

**Parametrized tests:**
```kotlin
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@ParameterizedTest
@ValueSource(strings = ["", "   ", "a", "x".repeat(256)])
fun `should reject invalid names`(invalidName: String) {
    val request = TestDataFactory.createIntegrationRequest(name = invalidName)

    given {
        contentType(ContentType.JSON)
        body(request)
        header("Authorization", "Bearer $userToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(400)
        body("violations.field", hasItem("name"))
    }
}
```

**Backend test checklist:**
- ✅ Success (201/200)
- ✅ Validation errors (400)
- ✅ Authentication (401 - no/invalid token)
- ✅ Authorization (403 - insufficient permissions)
- ✅ Not found (404)
- ✅ Use test data factories
- ✅ Generate unique emails with timestamp

---

## Security Testing

**Authentication & Authorization:**
```kotlin
@Test
fun `should reject request without authentication token`() {
    val request = TestDataFactory.createIntegrationRequest()

    given {
        contentType(ContentType.JSON)
        body(request)
        // No Authorization header
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(401)
    }
}

@Test
fun `should reject regular user accessing admin endpoint`() {
    given {
        header("Authorization", "Bearer $userToken")
    } When {
        get("/api/v1/admin/users")
    } Then {
        statusCode(403)
    }
}

@Test
fun `should prevent access to other users resources`() {
    // Create resource for another user
    val otherUser = TestDataFactory.createUser()
    userRepository.persist(otherUser)
    val otherUserToken = jwtService.generateToken(otherUser)

    val resourceId = given {
        contentType(ContentType.JSON)
        body(TestDataFactory.createIntegrationRequest())
        header("Authorization", "Bearer $otherUserToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(201)
        extract().path<String>("id")
    }

    // Try to access with different user
    given {
        header("Authorization", "Bearer $userToken")
    } When {
        get("/api/v1/integrations/$resourceId")
    } Then {
        statusCode(404) // Should not reveal existence
    }
}
```

**Input validation (verify defense):**
```kotlin
@Test
fun `should safely handle SQL injection as literal string`() {
    val maliciousInput = "'; DROP TABLE integrations; --"
    val request = TestDataFactory.createIntegrationRequest(name = maliciousInput)

    val id = given {
        contentType(ContentType.JSON)
        body(request)
        header("Authorization", "Bearer $userToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(201)
        body("name", equalTo(maliciousInput)) // Stored as literal string
        extract().path<String>("id")
    }

    // Verify table still exists
    given {
        header("Authorization", "Bearer $userToken")
    } When {
        get("/api/v1/integrations/$id")
    } Then {
        statusCode(200)
    }
}

@Test
fun `should reject dangerous URL formats`() {
    listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,<script>").forEach { url ->
        given {
            contentType(ContentType.JSON)
            body(TestDataFactory.createIntegrationRequest(apiUrl = url))
            header("Authorization", "Bearer $userToken")
        } When {
            post("/api/v1/integrations")
        } Then {
            statusCode(400)
        }
    }
}
```

**Sensitive data:**
```kotlin
@Test
fun `should not expose password hash in user response`() {
    given {
        header("Authorization", "Bearer $userToken")
    } When {
        get("/api/v1/users/me")
    } Then {
        statusCode(200)
        body("email", notNullValue())
        body("passwordHash", nullValue()) // Must not expose
    }
}

@Test
fun `should not expose credentials in integration response`() {
    val request = TestDataFactory.createIntegrationRequest(
        authType = AuthType.API_KEY,
        authCredentials = mapOf("apiKey" to "secret-key")
    )

    val id = given {
        contentType(ContentType.JSON)
        body(request)
        header("Authorization", "Bearer $userToken")
    } When {
        post("/api/v1/integrations")
    } Then {
        statusCode(201)
        extract().path<String>("id")
    }

    given {
        header("Authorization", "Bearer $userToken")
    } When {
        get("/api/v1/integrations/$id")
    } Then {
        statusCode(200)
        body("authCredentials", nullValue()) // Must not expose
    }
}
```

**Frontend security:**
```typescript
describe('Protected Routes', () => {
  it('should redirect to login without token', async () => {
    vi.spyOn(authStorage, 'getToken').mockReturnValue(null)
    renderWithProviders(<ProfilePage />, { initialRoute: '/profile' })

    await waitFor(() => {
      expect(window.location.pathname).toBe('/login')
    })
  })

  it('should hide admin features for regular users', () => {
    vi.spyOn(authStorage, 'isAdmin').mockReturnValue(false)
    renderWithProviders(<AppLayout><DashboardPage /></AppLayout>)

    expect(screen.queryByRole('link', { name: /admin/i })).not.toBeInTheDocument()
  })
})
```

**Security checklist:**
- [ ] Missing/invalid auth tokens rejected (401)
- [ ] Authorization enforces user boundaries (403, 404)
- [ ] Admin endpoints protected (403)
- [ ] Input validation prevents injection (SQL, XSS, SSRF)
- [ ] Sensitive data not exposed (passwords, credentials)
- [ ] Error messages don't leak internals

---

## Best Practices

**Test structure:**
- Use descriptive names: `should [expected behavior] when [condition]`
- One logical assertion per test
- Arrange-Act-Assert (Given-When-Then)
- Use test data factories for consistency
- Clean up in `afterEach` / `@BeforeEach`

**What to avoid:**
- ❌ Testing implementation details
- ❌ Shared mutable state between tests
- ❌ Copy-pasted test code
- ❌ Testing third-party libraries

**Coverage:**
```bash
# Frontend
npm run test -- --coverage

# Backend
./gradlew test jacocoTestReport
```

**Quick debugging:**
```typescript
// Frontend: see what rendered
screen.debug()

// Backend: print response
println("Response: ${extract().asString()}")
```
