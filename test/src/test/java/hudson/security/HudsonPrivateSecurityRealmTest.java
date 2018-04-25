package hudson.security;

import static hudson.security.HudsonPrivateSecurityRealm.CLASSIC;
import static hudson.security.HudsonPrivateSecurityRealm.PASSWORD_ENCODER;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.xml.HasXPath.hasXPath;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;

import hudson.security.pages.SignupPage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.recipes.LocalData;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.xml.XmlPage;

import hudson.model.User;
import hudson.remoting.Base64;
import jenkins.security.ApiTokenProperty;

/**
 * @author Kohsuke Kawaguchi
 */
public class HudsonPrivateSecurityRealmTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setup() throws Exception {
        Field field = HudsonPrivateSecurityRealm.class.getDeclaredField("ID_REGEX");
        field.setAccessible(true);
        field.set(null, null);
    }

    /**
     * Tests the data compatibility with Hudson before 1.283.
     * Starting 1.283, passwords are now stored hashed.
     */
    @Test
    @Issue("JENKINS-2381")
    @LocalData
    public void dataCompatibilityWith1_282() throws Exception {
        // make sure we can login with the same password as before
        WebClient wc = j.createWebClient().login("alice", "alice");

        try {
            // verify the sanity that the password is really used
            // this should fail
            j.createWebClient().login("bob", "bob");
        } catch (FailingHttpStatusCodeException e) {
            assertEquals(401,e.getStatusCode());
        }

        // resubmit the config and this should force the data store to be rewritten
        HtmlPage p = wc.goTo("user/alice/configure");
        j.submit(p.getFormByName("config"));

        // verify that we can still login
        j.createWebClient().login("alice", "alice");
    }

    @Test
    @WithoutJenkins
    public void hashCompatibility() {
        String old = CLASSIC.encodePassword("hello world", null);
        assertTrue(PASSWORD_ENCODER.isPasswordValid(old,"hello world",null));

        String secure = PASSWORD_ENCODER.encodePassword("hello world", null);
        assertTrue(PASSWORD_ENCODER.isPasswordValid(old,"hello world",null));

        assertFalse(secure.equals(old));
    }


    @Issue("SECURITY-243")
    @Test
    public void fullNameCollisionPassword() throws Exception {
        HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(false, false, null);
        j.jenkins.setSecurityRealm(securityRealm);
        
        User u1 = securityRealm.createAccount("user1", "password1");
        u1.setFullName("User One");
        u1.save();

        User u2 = securityRealm.createAccount("user2", "password2");
        u2.setFullName("User Two");
        u2.save();

        WebClient wc1 = j.createWebClient();
        wc1.login("user1", "password1");

        WebClient wc2 = j.createWebClient();
        wc2.login("user2", "password2");

        
        // Check both users can use their token
        XmlPage w1 = (XmlPage) wc1.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w1, hasXPath("//name", is("user1")));
        
        XmlPage w2 = (XmlPage) wc2.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w2, hasXPath("//name", is("user2")));

        u1.setFullName("user2");
        u1.save();
        
        // check the tokens still work
        wc1 = j.createWebClient();
        wc1.login("user1", "password1");

        wc2 = j.createWebClient();
        // throws FailingHttpStatusCodeException on login failure
        wc2.login("user2", "password2");

        // belt and braces incase the failed login no longer throws exceptions.
        w1 = (XmlPage) wc1.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w1, hasXPath("//name", is("user1")));
        
        w2 = (XmlPage) wc2.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w2, hasXPath("//name", is("user2")));
    }

    @Issue("SECURITY-243")
    @Test
    public void fullNameCollisionToken() throws Exception {
        HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(false, false, null);
        j.jenkins.setSecurityRealm(securityRealm);
        
        User u1 = securityRealm.createAccount("user1", "password1");
        u1.setFullName("User One");
        u1.save();
        String u1Token = u1.getProperty(ApiTokenProperty.class).getApiToken();

        User u2 = securityRealm.createAccount("user2", "password2");
        u2.setFullName("User Two");
        u2.save();
        String u2Token = u2.getProperty(ApiTokenProperty.class).getApiToken();

        WebClient wc1 = j.createWebClient();
        wc1.addRequestHeader("Authorization", basicHeader("user1", u1Token));
        //wc1.setCredentialsProvider(new FixedCredentialsProvider("user1", u1Token));

        WebClient wc2 = j.createWebClient();
        wc2.addRequestHeader("Authorization", basicHeader("user2", u2Token));
        //wc2.setCredentialsProvider(new FixedCredentialsProvider("user2", u1Token));
        
        // Check both users can use their token
        XmlPage w1 = (XmlPage) wc1.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w1, hasXPath("//name", is("user1")));
        
        XmlPage w2 = (XmlPage) wc2.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w2, hasXPath("//name", is("user2")));


        u1.setFullName("user2");
        u1.save();
        // check the tokens still work
        w1 = (XmlPage) wc1.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w1, hasXPath("//name", is("user1")));
        
        w2 = (XmlPage) wc2.goTo("whoAmI/api/xml", "application/xml");
        assertThat(w2, hasXPath("//name", is("user2")));
    }


    private static final String basicHeader(String user, String pass) throws UnsupportedEncodingException {
        String str = user +':' + pass;
        String auth = Base64.encode(str.getBytes("US-ASCII"));
        String authHeader = "Basic " + auth;
        return authHeader;
    }
    
    @Issue("SECURITY-786")
    @Test
    public void controlCharacterAreNoMoreValid() throws Exception {
        HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(true, false, null);
        j.jenkins.setSecurityRealm(securityRealm);
        
        String password = "testPwd";
        String email = "test@test.com";
        int i = 0;
        
        // regular case = only accepting a-zA-Z0-9 + "-_"
        checkUserCanBeCreatedWith(securityRealm, "test" + i, password, "Test" + i, email);
        assertNotNull(User.getById("test" + i, false));
        i++;
        checkUserCanBeCreatedWith(securityRealm, "te-st_123" + i, password, "Test" + i, email);
        assertNotNull(User.getById("te-st_123" + i, false));
        i++;
        {// user id that contains invalid characters
            checkUserCannotBeCreatedWith(securityRealm, "test " + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "te@st" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "test.com" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "test,com" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "test,com" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "testécom" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "Stargåte" + i, password, "Test" + i, email);
            i++;
            checkUserCannotBeCreatedWith(securityRealm, "te\u0000st" + i, password, "Test" + i, email);
            i++;
        }
    }
    
    @Issue("SECURITY-786")
    @Test
    public void controlCharacterAreNoMoreValid_CustomRegex() throws Exception {
        HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(true, false, null);
        j.jenkins.setSecurityRealm(securityRealm);
        
        String currentRegex = "^[A-Z]+[0-9]*$";
        
        Field field = HudsonPrivateSecurityRealm.class.getDeclaredField("ID_REGEX");
        field.setAccessible(true);
        field.set(null, currentRegex);
        
        String password = "testPwd";
        String email = "test@test.com";
        int i = 0;
        
        // regular case = only accepting a-zA-Z0-9 + "-_"
        checkUserCanBeCreatedWith(securityRealm, "TEST" + i, password, "Test" + i, email);
        assertNotNull(User.getById("TEST" + i, false));
        i++;
        checkUserCanBeCreatedWith(securityRealm, "TEST123" + i, password, "Test" + i, email);
        assertNotNull(User.getById("TEST123" + i, false));
        i++;
        {// user id that do not follow custom regex
            checkUserCannotBeCreatedWith_custom(securityRealm, "test " + i, password, "Test" + i, email, currentRegex);
            i++;
            checkUserCannotBeCreatedWith_custom(securityRealm, "@" + i, password, "Test" + i, email, currentRegex);
            i++;
            checkUserCannotBeCreatedWith_custom(securityRealm, "T2A" + i, password, "Test" + i, email, currentRegex);
            i++;
        }
        { // we can even change regex on the fly
            currentRegex = "^[0-9]*$";
            field.set(null, currentRegex);
    
            checkUserCanBeCreatedWith(securityRealm, "125213" + i, password, "Test" + i, email);
            assertNotNull(User.getById("125213" + i, false));
            i++;
            checkUserCannotBeCreatedWith_custom(securityRealm, "TEST12" + i, password, "Test" + i, email, currentRegex);
            i++;
        }
    }
    
    private void checkUserCanBeCreatedWith(HudsonPrivateSecurityRealm securityRealm, String id, String password, String fullName, String email) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        SignupPage signup = new SignupPage(wc.goTo("signup"));
        signup.enterUsername(id);
        signup.enterPassword(password);
        signup.enterFullName(fullName);
        signup.enterEmail(email);
        HtmlPage success = signup.submit(j);
        assertThat(success.getElementById("main-panel").getTextContent(), containsString("Success"));
    }
    
    private void checkUserCannotBeCreatedWith(HudsonPrivateSecurityRealm securityRealm, String id, String password, String fullName, String email) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        SignupPage signup = new SignupPage(wc.goTo("signup"));
        signup.enterUsername(id);
        signup.enterPassword(password);
        signup.enterFullName(fullName);
        signup.enterEmail(email);
        HtmlPage success = signup.submit(j);
        assertThat(success.getElementById("main-panel").getTextContent(), not(containsString("Success")));
        assertThat(success.getElementById("main-panel").getTextContent(), containsString(Messages.HudsonPrivateSecurityRealm_CreateAccount_UserNameInvalidCharacters()));
    }
    
    private void checkUserCannotBeCreatedWith_custom(HudsonPrivateSecurityRealm securityRealm, String id, String password, String fullName, String email, String regex) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        SignupPage signup = new SignupPage(wc.goTo("signup"));
        signup.enterUsername(id);
        signup.enterPassword(password);
        signup.enterFullName(fullName);
        signup.enterEmail(email);
        HtmlPage success = signup.submit(j);
        assertThat(success.getElementById("main-panel").getTextContent(), not(containsString("Success")));
        assertThat(success.getElementById("main-panel").getTextContent(), containsString(regex));
    }
}
